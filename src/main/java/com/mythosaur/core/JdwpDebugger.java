package com.mythosaur.core;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase B — a smali-level debugger built on JDI (the JDK's own Java Debug Interface),
 * which speaks JDWP to a debuggable Android app. We never re-implement the wire
 * protocol; JDI is the engine (the same one Android Studio uses). This class adds the
 * RE-specific glue: breakpoints addressed by <em>dalvik code index</em> (= the smali
 * instruction offset), deferred breakpoint resolution via class-prepare, instruction
 * (STEP_MIN) stepping, and paused-frame register/local inspection.
 *
 * <p>Connect by attaching to a local TCP port that {@link AdbBridge#forwardJdwp(int)}
 * has wired to the app's JDWP channel.
 */
public class JdwpDebugger {

    public interface Listener {
        void onAttached(String vmName);
        void onPaused(PauseInfo info);
        void onResumed();
        void onDetached(String reason);
        void onLog(String message);
    }

    public record VarValue(String name, String type, String value) {}
    public record FrameInfo(String className, String methodName, String methodSig, long codeIndex) {
        public String label() { return className + "." + methodName + "  @0x" + Long.toHexString(codeIndex); }
    }
    public static final class PauseInfo {
        public String threadName;
        public final List<FrameInfo> frames = new ArrayList<>();
        public FrameInfo top;
        public final List<VarValue> locals = new ArrayList<>();
        public boolean localsAvailable;     // false when the var table is stripped (obfuscated app)
    }

    /** A breakpoint request addressed the way a reverse engineer thinks: class+method+offset. */
    public record Bp(String className, String methodName, String methodSig, long codeIndex) {
        public String key() { return className + "->" + methodName + methodSig + "@" + codeIndex; }
    }

    private final Listener listener;
    private volatile VirtualMachine vm;
    private volatile boolean running;
    private Thread eventThread;

    private final Set<Bp> pending = new HashSet<>();          // requested breakpoints (armed even before attach)
    private final Map<String, BreakpointRequest> active = new java.util.HashMap<>();
    private ThreadReference pausedThread;                      // the thread suspended at a breakpoint/step

    public JdwpDebugger(Listener listener) { this.listener = listener; }

    public boolean isAttached() { return vm != null; }
    public boolean isPaused() { return pausedThread != null; }

    // ---------- attach / detach ----------

    public void attach(String host, int port) throws Exception {
        AttachingConnector connector = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (c.name().equals("com.sun.jdi.SocketAttach")) { connector = c; break; }
        }
        if (connector == null) throw new IllegalStateException("JDI SocketAttach connector not available");

        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(Integer.toString(port));

        vm = connector.attach(args);
        listener.onAttached(vm.name());

        // arm class-prepare requests + resolve any already-loaded target classes
        for (String cls : classesOfPending()) {
            installClassPrepare(cls);
            for (ReferenceType rt : vm.classesByName(cls)) resolveBreakpointsIn(rt);
        }

        running = true;
        eventThread = new Thread(this::eventLoop, "jdwp-events");
        eventThread.setDaemon(true);
        eventThread.start();
        vm.resume();
    }

    public void detach() {
        running = false;
        VirtualMachine v = vm;
        if (v != null) {
            try { v.dispose(); } catch (Exception ignored) {}
        }
        vm = null;
        pausedThread = null;
        active.clear();
        listener.onDetached("detached by user");
    }

    // ---------- breakpoints ----------

    public synchronized void addBreakpoint(Bp bp) {
        pending.add(bp);
        if (vm != null) {
            installClassPrepare(bp.className());
            for (ReferenceType rt : vm.classesByName(bp.className())) resolveBreakpointsIn(rt);
        }
    }

    /** All armed breakpoints (across every method) — the UI mirrors this to draw gutters. */
    public synchronized Set<Bp> breakpoints() { return new HashSet<>(pending); }

    public synchronized void removeBreakpoint(Bp bp) {
        pending.remove(bp);
        BreakpointRequest r = active.remove(bp.key());
        if (r != null && vm != null) {
            try { vm.eventRequestManager().deleteEventRequest(r); } catch (Exception ignored) {}
        }
    }

    private Set<String> classesOfPending() {
        Set<String> s = new HashSet<>();
        for (Bp b : pending) s.add(b.className());
        return s;
    }

    private final Set<String> classPrepareArmed = new HashSet<>();
    private void installClassPrepare(String cls) {
        if (vm == null || classPrepareArmed.contains(cls)) return;
        ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
        cpr.addClassFilter(cls);
        cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        cpr.enable();
        classPrepareArmed.add(cls);
    }

    private synchronized void resolveBreakpointsIn(ReferenceType rt) {
        for (Bp bp : pending) {
            if (!bp.className().equals(rt.name())) continue;
            if (active.containsKey(bp.key())) continue;
            Location loc = locationFor(rt, bp);
            if (loc == null) { listener.onLog("could not place breakpoint " + bp.key()); continue; }
            BreakpointRequest req = vm.eventRequestManager().createBreakpointRequest(loc);
            req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            req.enable();
            active.put(bp.key(), req);
            listener.onLog("breakpoint set: " + bp.key());
        }
    }

    private Location locationFor(ReferenceType rt, Bp bp) {
        for (Method m : rt.methodsByName(bp.methodName())) {
            if (bp.methodSig() != null && !bp.methodSig().isEmpty() && !m.signature().equals(bp.methodSig())) continue;
            Location loc = m.locationOfCodeIndex(bp.codeIndex());
            if (loc != null) return loc;
            // fall back to the method entry if the exact index isn't a valid location
            if (m.location() != null) return m.location();
        }
        return null;
    }

    // ---------- execution control ----------

    public synchronized void resume() {
        if (vm == null) return;
        clearStep();
        pausedThread = null;
        vm.resume();
        listener.onResumed();
    }

    public void stepInto() { step(StepRequest.STEP_INTO); }
    public void stepOver() { step(StepRequest.STEP_OVER); }
    public void stepOut()  { step(StepRequest.STEP_OUT); }

    private synchronized void step(int depth) {
        if (vm == null || pausedThread == null) return;
        clearStep();
        // STEP_MIN = advance by the smallest code unit -> smali-instruction granularity
        StepRequest sr = vm.eventRequestManager().createStepRequest(pausedThread, StepRequest.STEP_MIN, depth);
        sr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        sr.addCountFilter(1);
        sr.enable();
        ThreadReference t = pausedThread;
        pausedThread = null;
        t.resume();
    }

    private void clearStep() {
        if (vm == null) return;
        List<StepRequest> steps = new ArrayList<>(vm.eventRequestManager().stepRequests());
        for (StepRequest sr : steps) {
            try { vm.eventRequestManager().deleteEventRequest(sr); } catch (Exception ignored) {}
        }
    }

    // ---------- event loop ----------

    private void eventLoop() {
        try {
            EventQueue queue = vm.eventQueue();
            while (running) {
                EventSet set = queue.remove();
                boolean stayPaused = false;
                for (Event ev : set) {
                    if (ev instanceof ClassPrepareEvent cpe) {
                        resolveBreakpointsIn(cpe.referenceType());
                    } else if (ev instanceof BreakpointEvent be) {
                        stayPaused = true;
                        handlePause(be);
                    } else if (ev instanceof StepEvent se) {
                        stayPaused = true;
                        clearStep();
                        handlePause(se);
                    } else if (ev instanceof VMDeathEvent || ev instanceof VMDisconnectEvent) {
                        running = false;
                        listener.onDetached("target VM terminated");
                        return;
                    }
                }
                if (!stayPaused) set.resume();   // let non-suspending events flow
            }
        } catch (com.sun.jdi.VMDisconnectedException | InterruptedException e) {
            if (running) listener.onDetached("disconnected");
        } catch (Exception e) {
            if (running) listener.onDetached("event loop error: " + e.getMessage());
        }
    }

    private void handlePause(LocatableEvent ev) {
        pausedThread = ev.thread();
        PauseInfo info = new PauseInfo();
        try {
            info.threadName = pausedThread.name();
            List<StackFrame> frames = pausedThread.frames();
            for (StackFrame f : frames) info.frames.add(frameInfo(f.location()));
            if (!info.frames.isEmpty()) info.top = info.frames.get(0);
            readLocals(frames.isEmpty() ? null : frames.get(0), info);
        } catch (Exception e) {
            listener.onLog("frame read error: " + e.getMessage());
        }
        listener.onPaused(info);
    }

    private FrameInfo frameInfo(Location loc) {
        Method m = loc.method();
        return new FrameInfo(loc.declaringType().name(), m.name(), m.signature(), loc.codeIndex());
    }

    private void readLocals(StackFrame frame, PauseInfo info) {
        if (frame == null) return;
        // `this`
        try {
            ObjectReference self = frame.thisObject();
            if (self != null) info.locals.add(new VarValue("this", self.referenceType().name(), renderRef(self)));
        } catch (Exception ignored) {}
        // declared locals (only present if the var table wasn't stripped)
        try {
            List<LocalVariable> vars = frame.visibleVariables();
            info.localsAvailable = true;
            Map<LocalVariable, Value> vals = frame.getValues(vars);
            for (LocalVariable v : vars) {
                info.locals.add(new VarValue(v.name(), v.typeName(), render(vals.get(v))));
            }
        } catch (AbsentInformationException e) {
            info.localsAvailable = false;     // obfuscated/release app: registers not named
            // still surface the argument values, which are available without a full table
            try {
                List<Value> argv = frame.getArgumentValues();
                for (int i = 0; i < argv.size(); i++) {
                    info.locals.add(new VarValue("arg" + i, "?", render(argv.get(i))));
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private String render(Value v) {
        if (v == null) return "null";
        if (v instanceof StringReference s) return "\"" + s.value() + "\"";
        if (v instanceof ObjectReference o) return renderRef(o);
        return v.toString();
    }

    private String renderRef(ObjectReference o) {
        try { return o.referenceType().name() + "@" + o.uniqueID(); }
        catch (Exception e) { return "<obj>"; }
    }
}
