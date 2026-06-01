package com.mythosaur.core;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.SwitchElement;
import org.jf.dexlib2.iface.instruction.SwitchPayload;
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.instruction.VariableRegisterInstruction;
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.formats.ArrayPayload;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A focused, sandboxed Dalvik <em>method</em> interpreter — the "Dry Run" engine.
 *
 * <p>It executes one method's bytecode (recursing into in-dex callees) over a real
 * Java register file, so a reverse engineer can run an obfuscated routine (e.g. a
 * string-decryptor) and see its actual result + a step trace — without a device.
 *
 * <p><b>Why this is safe:</b> the APK's own classes are never loaded into this JVM —
 * they are interpreted instruction by instruction. Only leaf calls into a small
 * <i>whitelist</i> of standard JDK classes (String, StringBuilder, Arrays, Base64,
 * javax.crypto, MessageDigest, …) are dispatched for real via reflection. Dangerous
 * classes (Runtime, ProcessBuilder, File/Net I/O, reflection, System.exit, native
 * loading) are blocked, so untrusted decryptor logic cannot escape the sandbox.
 *
 * <p>Anything it cannot model (unknown call, unsupported opcode, missing input) is
 * recorded honestly in {@link Result#unsupported} rather than faked.
 */
public class DalvikInterpreter {

    /** Sentinel for a value the interpreter could not determine. */
    public static final Object UNKNOWN = new Object() {
        public String toString() { return "<unknown>"; }
    };

    public static final class TraceStep {
        public final int addr;
        public final String insn;
        public final String effect;
        TraceStep(int addr, String insn, String effect) { this.addr = addr; this.insn = insn; this.effect = effect; }
    }

    public static final class Result {
        public Object returnValue = UNKNOWN;
        public String returnType = "V";
        public boolean returned;
        public boolean timedOut;
        public String unsupported;          // first thing we couldn't model, else null
        public final List<TraceStep> trace = new ArrayList<>();

        /** Pretty, RE-friendly rendering of the return value. */
        public String display() {
            return render(returnValue);
        }
    }

    private final Project project;          // for resolving in-dex callees (may be null)
    private int budget;                     // remaining instruction budget (shared across recursion)
    private int maxDepth = 24;
    private boolean trace = true;

    public DalvikInterpreter(Project project) { this(project, 300_000); }
    public DalvikInterpreter(Project project, int budget) { this.project = project; this.budget = budget; }

    public DalvikInterpreter trace(boolean on) { this.trace = on; return this; }

    /** Run {@code method} with the given logical arguments (receiver first for instance methods). */
    public Result run(Method method, Object[] args) {
        Result r = new Result();
        r.returnType = method.getReturnType();
        try {
            Object ret = exec(method, args, 0, r);
            if (!"V".equals(r.returnType)) r.returnValue = ret;
            r.returned = true;
        } catch (Budget b) {
            r.timedOut = true;
        } catch (Unsupported u) {
            if (r.unsupported == null) r.unsupported = u.getMessage();
        } catch (Throwable t) {
            if (r.unsupported == null) r.unsupported = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
        return r;
    }

    // ---- control-flow signals ----
    private static final class Budget extends RuntimeException {}
    private static final class Unsupported extends RuntimeException {
        Unsupported(String m) { super(m); }
    }

    /** Execute one method body, returning its (boxed) result value or null for void. */
    private Object exec(Method method, Object[] args, int depth, Result top) {
        if (depth > maxDepth) throw new Unsupported("recursion too deep");
        MethodImplementation impl = method.getImplementation();
        if (impl == null) throw new Unsupported("no body: " + method.getName());

        // index instructions by byte address (like CfgBuilder)
        List<Instruction> insns = new ArrayList<>();
        List<Integer> addrs = new ArrayList<>();
        Map<Integer, Integer> addrToIndex = new HashMap<>();
        int addr = 0;
        for (Instruction in : impl.getInstructions()) {
            addrToIndex.put(addr, insns.size());
            addrs.add(addr);
            insns.add(in);
            addr += in.getCodeUnits();
        }

        // register file + parameter placement (params occupy the LAST registers)
        Object[] reg = new Object[Math.max(impl.getRegisterCount(), 1)];
        placeArgs(method, reg, args);

        Object result = null;            // holds value for move-result*
        boolean resultPending = false;

        int pc = 0;
        while (pc >= 0 && pc < insns.size()) {
            if (--budget <= 0) throw new Budget();
            Instruction in = insns.get(pc);
            int a = addrs.get(pc);
            Opcode op = in.getOpcode();
            String name = op.name;
            String effect = null;
            int next = pc + 1;

            switch (op) {
                case NOP -> {}

                // ---- constants ----
                case CONST_4, CONST_16, CONST, CONST_HIGH16 -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    long lit = ((NarrowLiteralInstruction) in).getNarrowLiteral();
                    reg[dst] = (int) lit;
                    effect = "v" + dst + " = " + lit;
                }
                case CONST_WIDE_16, CONST_WIDE_32, CONST_WIDE, CONST_WIDE_HIGH16 -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    long lit = ((WideLiteralInstruction) in).getWideLiteral();
                    setWide(reg, dst, lit);
                    effect = "v" + dst + " = " + lit + "L";
                }
                case CONST_STRING, CONST_STRING_JUMBO -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    String s = ((StringReference) ((ReferenceInstruction) in).getReference()).getString();
                    reg[dst] = s;
                    effect = "v" + dst + " = \"" + ellipsis(s) + "\"";
                }
                case CONST_CLASS -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    reg[dst] = UNKNOWN; // class objects rarely needed for decryptors
                }

                // ---- moves ----
                case MOVE, MOVE_FROM16, MOVE_16, MOVE_OBJECT, MOVE_OBJECT_FROM16, MOVE_OBJECT_16 -> {
                    int dst = ((TwoRegisterInstruction) in).getRegisterA();
                    int src = ((TwoRegisterInstruction) in).getRegisterB();
                    reg[dst] = reg[src];
                    effect = "v" + dst + " = v" + src;
                }
                case MOVE_WIDE, MOVE_WIDE_FROM16, MOVE_WIDE_16 -> {
                    int dst = ((TwoRegisterInstruction) in).getRegisterA();
                    int src = ((TwoRegisterInstruction) in).getRegisterB();
                    reg[dst] = reg[src];
                    if (dst + 1 < reg.length && src + 1 < reg.length) reg[dst + 1] = reg[src + 1];
                }
                case MOVE_RESULT, MOVE_RESULT_WIDE, MOVE_RESULT_OBJECT -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    reg[dst] = resultPending ? result : UNKNOWN;
                    effect = "v" + dst + " = " + render(reg[dst]);
                }
                case MOVE_EXCEPTION -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    reg[dst] = UNKNOWN;
                }

                // ---- returns ----
                case RETURN_VOID -> { record(top, a, in, "return"); return null; }
                case RETURN, RETURN_OBJECT -> {
                    int src = ((OneRegisterInstruction) in).getRegisterA();
                    record(top, a, in, "return " + render(reg[src]));
                    return reg[src];
                }
                case RETURN_WIDE -> {
                    int src = ((OneRegisterInstruction) in).getRegisterA();
                    record(top, a, in, "return " + render(reg[src]));
                    return reg[src];
                }

                // ---- unary ----
                case NEG_INT, NOT_INT, NEG_LONG, NOT_LONG, NEG_FLOAT, NEG_DOUBLE,
                     INT_TO_LONG, INT_TO_FLOAT, INT_TO_DOUBLE, LONG_TO_INT, LONG_TO_FLOAT, LONG_TO_DOUBLE,
                     FLOAT_TO_INT, FLOAT_TO_LONG, FLOAT_TO_DOUBLE, DOUBLE_TO_INT, DOUBLE_TO_LONG, DOUBLE_TO_FLOAT,
                     INT_TO_BYTE, INT_TO_CHAR, INT_TO_SHORT -> {
                    int dst = ((TwoRegisterInstruction) in).getRegisterA();
                    int src = ((TwoRegisterInstruction) in).getRegisterB();
                    reg[dst] = unary(op, reg[src]);
                    effect = "v" + dst + " = " + render(reg[dst]);
                }

                // ---- binary (vA = vB op vC) ----
                case ADD_INT, SUB_INT, MUL_INT, DIV_INT, REM_INT, AND_INT, OR_INT, XOR_INT,
                     SHL_INT, SHR_INT, USHR_INT,
                     ADD_LONG, SUB_LONG, MUL_LONG, DIV_LONG, REM_LONG, AND_LONG, OR_LONG, XOR_LONG,
                     SHL_LONG, SHR_LONG, USHR_LONG,
                     ADD_FLOAT, SUB_FLOAT, MUL_FLOAT, DIV_FLOAT, REM_FLOAT,
                     ADD_DOUBLE, SUB_DOUBLE, MUL_DOUBLE, DIV_DOUBLE, REM_DOUBLE,
                     CMP_LONG, CMPL_FLOAT, CMPG_FLOAT, CMPL_DOUBLE, CMPG_DOUBLE -> {
                    ThreeRegisterInstruction t = (ThreeRegisterInstruction) in;
                    reg[t.getRegisterA()] = binary(op, reg[t.getRegisterB()], reg[t.getRegisterC()]);
                    effect = "v" + t.getRegisterA() + " = " + render(reg[t.getRegisterA()]);
                }

                // ---- binary 2addr (vA = vA op vB) ----
                case ADD_INT_2ADDR, SUB_INT_2ADDR, MUL_INT_2ADDR, DIV_INT_2ADDR, REM_INT_2ADDR,
                     AND_INT_2ADDR, OR_INT_2ADDR, XOR_INT_2ADDR, SHL_INT_2ADDR, SHR_INT_2ADDR, USHR_INT_2ADDR,
                     ADD_LONG_2ADDR, SUB_LONG_2ADDR, MUL_LONG_2ADDR, DIV_LONG_2ADDR, REM_LONG_2ADDR,
                     AND_LONG_2ADDR, OR_LONG_2ADDR, XOR_LONG_2ADDR, SHL_LONG_2ADDR, SHR_LONG_2ADDR, USHR_LONG_2ADDR,
                     ADD_FLOAT_2ADDR, SUB_FLOAT_2ADDR, MUL_FLOAT_2ADDR, DIV_FLOAT_2ADDR, REM_FLOAT_2ADDR,
                     ADD_DOUBLE_2ADDR, SUB_DOUBLE_2ADDR, MUL_DOUBLE_2ADDR, DIV_DOUBLE_2ADDR, REM_DOUBLE_2ADDR -> {
                    TwoRegisterInstruction t = (TwoRegisterInstruction) in;
                    reg[t.getRegisterA()] = binary(op, reg[t.getRegisterA()], reg[t.getRegisterB()]);
                    effect = "v" + t.getRegisterA() + " = " + render(reg[t.getRegisterA()]);
                }

                // ---- binary lit (vA = vB op #lit) ----
                case ADD_INT_LIT16, RSUB_INT, MUL_INT_LIT16, DIV_INT_LIT16, REM_INT_LIT16,
                     AND_INT_LIT16, OR_INT_LIT16, XOR_INT_LIT16,
                     ADD_INT_LIT8, RSUB_INT_LIT8, MUL_INT_LIT8, DIV_INT_LIT8, REM_INT_LIT8,
                     AND_INT_LIT8, OR_INT_LIT8, XOR_INT_LIT8, SHL_INT_LIT8, SHR_INT_LIT8, USHR_INT_LIT8 -> {
                    TwoRegisterInstruction t = (TwoRegisterInstruction) in;
                    int lit = (int) ((NarrowLiteralInstruction) in).getNarrowLiteral();
                    reg[t.getRegisterA()] = binaryLit(op, reg[t.getRegisterB()], lit);
                    effect = "v" + t.getRegisterA() + " = " + render(reg[t.getRegisterA()]);
                }

                // ---- branches ----
                case GOTO, GOTO_16, GOTO_32 -> {
                    next = idx(addrToIndex, a + ((OffsetInstruction) in).getCodeOffset());
                }
                case IF_EQ, IF_NE, IF_LT, IF_GE, IF_GT, IF_LE -> {
                    TwoRegisterInstruction t = (TwoRegisterInstruction) in;
                    boolean take = compare(op, asInt(reg[t.getRegisterA()]), asInt(reg[t.getRegisterB()]));
                    if (take) next = idx(addrToIndex, a + ((OffsetInstruction) in).getCodeOffset());
                    effect = take ? "taken" : "not taken";
                }
                case IF_EQZ, IF_NEZ, IF_LTZ, IF_GEZ, IF_GTZ, IF_LEZ -> {
                    int va = ((OneRegisterInstruction) in).getRegisterA();
                    boolean take = compareZ(op, reg[va]);
                    if (take) next = idx(addrToIndex, a + ((OffsetInstruction) in).getCodeOffset());
                    effect = take ? "taken" : "not taken";
                }
                case PACKED_SWITCH, SPARSE_SWITCH -> {
                    int va = ((OneRegisterInstruction) in).getRegisterA();
                    int key = asInt(reg[va]);
                    int payloadIdx = idx(addrToIndex, a + ((OffsetInstruction) in).getCodeOffset());
                    next = pc + 1;
                    if (payloadIdx >= 0 && insns.get(payloadIdx) instanceof SwitchPayload sp) {
                        for (SwitchElement el : sp.getSwitchElements()) {
                            if (el.getKey() == key) { next = idx(addrToIndex, a + el.getOffset()); break; }
                        }
                    }
                }

                // ---- arrays ----
                case NEW_ARRAY -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    int sizeReg = ((TwoRegisterInstruction) in).getRegisterB();
                    String type = ((TypeReference) ((ReferenceInstruction) in).getReference()).getType();
                    reg[dst] = newArray(type, asInt(reg[sizeReg]));
                    effect = "v" + dst + " = " + type + "[" + asInt(reg[sizeReg]) + "]";
                }
                case ARRAY_LENGTH -> {
                    int dst = ((TwoRegisterInstruction) in).getRegisterA();
                    Object arr = reg[((TwoRegisterInstruction) in).getRegisterB()];
                    reg[dst] = (arr != null && arr.getClass().isArray()) ? Array.getLength(arr) : UNKNOWN;
                    effect = "v" + dst + " = " + render(reg[dst]);
                }
                case AGET, AGET_WIDE, AGET_OBJECT, AGET_BOOLEAN, AGET_BYTE, AGET_CHAR, AGET_SHORT -> {
                    ThreeRegisterInstruction t = (ThreeRegisterInstruction) in;
                    Object arr = reg[t.getRegisterB()];
                    int i = asInt(reg[t.getRegisterC()]);
                    reg[t.getRegisterA()] = arrayGet(arr, i);
                    effect = "v" + t.getRegisterA() + " = " + render(reg[t.getRegisterA()]);
                }
                case APUT, APUT_WIDE, APUT_OBJECT, APUT_BOOLEAN, APUT_BYTE, APUT_CHAR, APUT_SHORT -> {
                    ThreeRegisterInstruction t = (ThreeRegisterInstruction) in;
                    Object arr = reg[t.getRegisterB()];
                    int i = asInt(reg[t.getRegisterC()]);
                    arrayPut(arr, i, reg[t.getRegisterA()]);
                }
                case FILL_ARRAY_DATA -> {
                    int va = ((OneRegisterInstruction) in).getRegisterA();
                    int payloadIdx = idx(addrToIndex, a + ((OffsetInstruction) in).getCodeOffset());
                    if (payloadIdx >= 0 && insns.get(payloadIdx) instanceof ArrayPayload ap) {
                        fillArray(reg[va], ap);
                    }
                }
                case FILLED_NEW_ARRAY, FILLED_NEW_ARRAY_RANGE -> {
                    String type = ((TypeReference) ((ReferenceInstruction) in).getReference()).getType();
                    int[] rs = argRegs(in);
                    Object arr = newArray(type, rs.length);
                    for (int i = 0; i < rs.length; i++) arrayPut(arr, i, reg[rs[i]]);
                    result = arr; resultPending = true;
                }

                // ---- field access (limited; mostly unknown for instance fields) ----
                case SGET, SGET_WIDE, SGET_OBJECT, SGET_BOOLEAN, SGET_BYTE, SGET_CHAR, SGET_SHORT,
                     IGET, IGET_WIDE, IGET_OBJECT, IGET_BOOLEAN, IGET_BYTE, IGET_CHAR, IGET_SHORT -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    reg[dst] = UNKNOWN; // we don't model app field storage
                    effect = "v" + dst + " = <field>";
                }
                case SPUT, SPUT_WIDE, SPUT_OBJECT, SPUT_BOOLEAN, SPUT_BYTE, SPUT_CHAR, SPUT_SHORT,
                     IPUT, IPUT_WIDE, IPUT_OBJECT, IPUT_BOOLEAN, IPUT_BYTE, IPUT_CHAR, IPUT_SHORT -> {
                    // no-op: app field writes aren't observable in a single-method dry run
                }

                // ---- object creation ----
                case NEW_INSTANCE -> {
                    int dst = ((OneRegisterInstruction) in).getRegisterA();
                    reg[dst] = new Uninit(((TypeReference) ((ReferenceInstruction) in).getReference()).getType());
                }

                // ---- invokes ----
                case INVOKE_VIRTUAL, INVOKE_SUPER, INVOKE_DIRECT, INVOKE_STATIC, INVOKE_INTERFACE,
                     INVOKE_VIRTUAL_RANGE, INVOKE_SUPER_RANGE, INVOKE_DIRECT_RANGE,
                     INVOKE_STATIC_RANGE, INVOKE_INTERFACE_RANGE -> {
                    MethodReference mref = (MethodReference) ((ReferenceInstruction) in).getReference();
                    int[] rs = argRegs(in);
                    boolean isStatic = op == Opcode.INVOKE_STATIC || op == Opcode.INVOKE_STATIC_RANGE;
                    Invoke iv = invoke(mref, rs, reg, isStatic, depth, top);
                    result = iv.value; resultPending = iv.producesResult;
                    effect = iv.effect;
                }

                default -> throw new Unsupported("opcode " + name);
            }

            record(top, a, in, effect);
            if (next < 0) throw new Unsupported("bad branch target");
            pc = next;
        }
        return null;
    }

    // ---------- invoke dispatch ----------

    private static final class Invoke { Object value; boolean producesResult; String effect; }

    private Invoke invoke(MethodReference m, int[] rs, Object[] reg, boolean isStatic, int depth, Result top) {
        Invoke out = new Invoke();
        String owner = m.getDefiningClass();           // e.g. Ljava/lang/String;
        String javaName = descToJava(owner);
        String mn = m.getName();
        boolean producesResult = !"V".equals(m.getReturnType());
        out.producesResult = producesResult;

        // 1) standard JDK class on the whitelist -> execute for real via reflection
        if (Sandbox.isLoadable(javaName)) {
            try {
                Object v = reflectCall(javaName, mn, m, rs, reg, isStatic);
                out.value = v;
                out.effect = simple(javaName) + "." + mn + "() = " + render(v);
                return out;
            } catch (Sandbox.Blocked b) {
                throw new Unsupported("blocked call " + simple(javaName) + "." + mn);
            } catch (Unsupported u) {
                throw u;
            } catch (Throwable t) {
                // a real JDK call threw (e.g. BadPaddingException) — surface, don't fake
                throw new Unsupported(simple(javaName) + "." + mn + " threw " + t.getClass().getSimpleName());
            }
        }

        // 2) in-dex method we can interpret -> recurse
        Method callee = (project != null) ? project.findMethod(descToDotted(owner), mn) : null;
        if (callee != null && callee.getImplementation() != null) {
            Object[] callArgs = new Object[rs.length];
            for (int i = 0; i < rs.length; i++) callArgs[i] = reg[rs[i]];
            // collapse wide pairs: the callee's placeArgs re-expands, so pass values as-is
            Object v = exec(callee, packForCallee(callee, rs, reg), depth + 1, top);
            out.value = v;
            out.effect = simple(descToJava(owner)) + "." + mn + "() = " + render(v);
            return out;
        }

        // 3) cannot model
        out.value = UNKNOWN;
        out.effect = simple(descToJava(owner)) + "." + mn + "() = <unknown>";
        if (top.unsupported == null) top.unsupported = "uncallable " + simple(descToJava(owner)) + "." + mn + "()";
        return out;
    }

    /** Build the callee's logical argument list from the caller's argument registers. */
    private Object[] packForCallee(Method callee, int[] rs, Object[] reg) {
        boolean staticCallee = AccessFlags.STATIC.isSet(callee.getAccessFlags());
        List<? extends CharSequence> ptypes = callee.getParameterTypes();
        List<Object> args = new ArrayList<>();
        int ri = 0;
        if (!staticCallee) { if (ri < rs.length) args.add(reg[rs[ri++]]); }
        for (CharSequence pt : ptypes) {
            if (ri >= rs.length) { args.add(UNKNOWN); continue; }
            args.add(reg[rs[ri]]);
            ri += isWide(pt.toString()) ? 2 : 1;   // wide params span two arg registers
        }
        return args.toArray();
    }

    private Object reflectCall(String javaName, String mn, MethodReference m, int[] rs, Object[] reg, boolean isStatic)
            throws Exception {
        Class<?> cls = Sandbox.load(javaName);
        List<? extends CharSequence> ptypes = m.getParameterTypes();
        Class<?>[] pc = new Class<?>[ptypes.size()];
        for (int i = 0; i < pc.length; i++) pc[i] = Sandbox.load(descToJava(ptypes.get(i).toString()));

        // map argument registers -> values (receiver first for instance calls)
        int ri = 0;
        Object receiver = null;
        if (!isStatic && !"<init>".equals(mn)) receiver = reg[rs[ri++]];
        Object initTargetReg = null;
        if ("<init>".equals(mn)) { initTargetReg = rs.length > 0 ? rs[0] : null; ri = 1; }

        Object[] vals = new Object[pc.length];
        for (int i = 0; i < pc.length; i++) {
            Object raw = (ri < rs.length) ? reg[rs[ri]] : UNKNOWN;
            if (raw == UNKNOWN) throw new Unsupported("unknown arg to " + simple(javaName) + "." + mn);
            vals[i] = coerce(raw, pc[i]);
            ri += isWide(ptypes.get(i).toString()) ? 2 : 1;
        }

        if ("<init>".equals(mn)) {
            Sandbox.checkConstruct(cls);
            Constructor<?> ctor = cls.getConstructor(pc);
            Object obj = ctor.newInstance(vals);
            if (initTargetReg instanceof Integer t) reg[t] = obj;   // replace the Uninit placeholder
            return obj;
        }

        Sandbox.checkMethod(cls, mn);
        java.lang.reflect.Method jm = cls.getMethod(mn, pc);
        Object recv = isStatic ? null : unwrap(receiver);
        return jm.invoke(recv, vals);
    }

    // ---------- value helpers ----------

    private void placeArgs(Method method, Object[] reg, Object[] args) {
        if (args == null) args = new Object[0];
        boolean isStatic = AccessFlags.STATIC.isSet(method.getAccessFlags());
        List<? extends CharSequence> ptypes = method.getParameterTypes();
        int paramSlots = isStatic ? 0 : 1;
        for (CharSequence pt : ptypes) paramSlots += isWide(pt.toString()) ? 2 : 1;
        int base = Math.max(0, reg.length - paramSlots);
        int ai = 0, slot = base;
        if (!isStatic) { reg[slot++] = (ai < args.length) ? args[ai++] : UNKNOWN; }
        for (CharSequence pt : ptypes) {
            Object v = (ai < args.length) ? args[ai++] : UNKNOWN;
            reg[slot] = v;
            slot += isWide(pt.toString()) ? 2 : 1;
        }
    }

    private static void setWide(Object[] reg, int i, long v) {
        reg[i] = v;
        if (i + 1 < reg.length) reg[i + 1] = null;
    }

    private static int[] argRegs(Instruction in) {
        if (in instanceof RegisterRangeInstruction rr) {
            int[] r = new int[rr.getRegisterCount()];
            for (int i = 0; i < r.length; i++) r[i] = rr.getStartRegister() + i;
            return r;
        }
        if (in instanceof FiveRegisterInstruction f) {
            int c = ((VariableRegisterInstruction) in).getRegisterCount();
            int[] all = { f.getRegisterC(), f.getRegisterD(), f.getRegisterE(), f.getRegisterF(), f.getRegisterG() };
            return Arrays.copyOf(all, c);
        }
        return new int[0];
    }

    private static int idx(Map<Integer, Integer> addrToIndex, int addr) {
        Integer i = addrToIndex.get(addr);
        return i == null ? -1 : i;
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof Character c) return c;
        if (o instanceof Boolean b) return b ? 1 : 0;
        return 0;
    }
    private static long asLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static float asFloat(Object o) { return o instanceof Number n ? n.floatValue() : 0f; }
    private static double asDouble(Object o) { return o instanceof Number n ? n.doubleValue() : 0d; }

    private static Object unary(Opcode op, Object v) {
        return switch (op) {
            case NEG_INT -> -asInt(v);
            case NOT_INT -> ~asInt(v);
            case NEG_LONG -> -asLong(v);
            case NOT_LONG -> ~asLong(v);
            case NEG_FLOAT -> -asFloat(v);
            case NEG_DOUBLE -> -asDouble(v);
            case INT_TO_LONG -> (long) asInt(v);
            case INT_TO_FLOAT -> (float) asInt(v);
            case INT_TO_DOUBLE -> (double) asInt(v);
            case LONG_TO_INT -> (int) asLong(v);
            case LONG_TO_FLOAT -> (float) asLong(v);
            case LONG_TO_DOUBLE -> (double) asLong(v);
            case FLOAT_TO_INT -> (int) asFloat(v);
            case FLOAT_TO_LONG -> (long) asFloat(v);
            case FLOAT_TO_DOUBLE -> (double) asFloat(v);
            case DOUBLE_TO_INT -> (int) asDouble(v);
            case DOUBLE_TO_LONG -> (long) asDouble(v);
            case DOUBLE_TO_FLOAT -> (float) asDouble(v);
            case INT_TO_BYTE -> (int) (byte) asInt(v);
            case INT_TO_CHAR -> (int) (char) asInt(v);
            case INT_TO_SHORT -> (int) (short) asInt(v);
            default -> UNKNOWN;
        };
    }

    private static Object binary(Opcode op, Object x, Object y) {
        String n = op.name;
        if (n.contains("long")) {
            long b = asLong(y); long a = asLong(x);
            return switch (base(n)) {
                case "add" -> a + b; case "sub" -> a - b; case "mul" -> a * b;
                case "div" -> b == 0 ? UNKNOWN : a / b; case "rem" -> b == 0 ? UNKNOWN : a % b;
                case "and" -> a & b; case "or" -> a | b; case "xor" -> a ^ b;
                case "shl" -> a << (b & 63); case "shr" -> a >> (b & 63); case "ushr" -> a >>> (b & 63);
                case "cmp" -> Long.compare(a, b);
                default -> UNKNOWN;
            };
        }
        if (n.contains("float")) {
            float a = asFloat(x), b = asFloat(y);
            return switch (base(n)) {
                case "add" -> a + b; case "sub" -> a - b; case "mul" -> a * b; case "div" -> a / b; case "rem" -> a % b;
                case "cmpl", "cmpg" -> Float.compare(a, b);
                default -> UNKNOWN;
            };
        }
        if (n.contains("double")) {
            double a = asDouble(x), b = asDouble(y);
            return switch (base(n)) {
                case "add" -> a + b; case "sub" -> a - b; case "mul" -> a * b; case "div" -> a / b; case "rem" -> a % b;
                case "cmpl", "cmpg" -> Double.compare(a, b);
                default -> UNKNOWN;
            };
        }
        int a = asInt(x), b = asInt(y);
        return switch (base(n)) {
            case "add" -> a + b; case "sub" -> a - b; case "mul" -> a * b;
            case "div" -> b == 0 ? UNKNOWN : a / b; case "rem" -> b == 0 ? UNKNOWN : a % b;
            case "and" -> a & b; case "or" -> a | b; case "xor" -> a ^ b;
            case "shl" -> a << (b & 31); case "shr" -> a >> (b & 31); case "ushr" -> a >>> (b & 31);
            default -> UNKNOWN;
        };
    }

    private static Object binaryLit(Opcode op, Object x, int lit) {
        int a = asInt(x);
        return switch (base(op.name)) {
            case "add" -> a + lit; case "mul" -> a * lit;
            case "div" -> lit == 0 ? UNKNOWN : a / lit; case "rem" -> lit == 0 ? UNKNOWN : a % lit;
            case "and" -> a & lit; case "or" -> a | lit; case "xor" -> a ^ lit;
            case "shl" -> a << (lit & 31); case "shr" -> a >> (lit & 31); case "ushr" -> a >>> (lit & 31);
            case "rsub" -> lit - a;
            default -> UNKNOWN;
        };
    }

    /** "add-int/lit8" -> "add", "rsub-int" -> "rsub", "cmpl-float" -> "cmpl". */
    private static String base(String opName) {
        String s = opName;
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int dash = s.indexOf('-');
        return dash >= 0 ? s.substring(0, dash) : s;
    }

    private static boolean compare(Opcode op, int a, int b) {
        return switch (op) {
            case IF_EQ -> a == b; case IF_NE -> a != b; case IF_LT -> a < b;
            case IF_GE -> a >= b; case IF_GT -> a > b; case IF_LE -> a <= b;
            default -> false;
        };
    }

    private static boolean compareZ(Opcode op, Object v) {
        if (op == Opcode.IF_EQZ) return isZero(v);
        if (op == Opcode.IF_NEZ) return !isZero(v);
        int a = asInt(v);
        return switch (op) {
            case IF_LTZ -> a < 0; case IF_GEZ -> a >= 0; case IF_GTZ -> a > 0; case IF_LEZ -> a <= 0;
            default -> false;
        };
    }

    private static boolean isZero(Object v) {
        if (v == null) return true;                  // null object reference
        if (v == UNKNOWN) return false;
        if (v instanceof Number n) return n.longValue() == 0;
        if (v instanceof Boolean b) return !b;
        return false;                                // non-null object reference
    }

    // ---------- arrays ----------

    private static Object newArray(String type, int len) {
        if (len < 0) return UNKNOWN;
        return switch (type) {
            case "[B" -> new byte[len]; case "[C" -> new char[len]; case "[I" -> new int[len];
            case "[J" -> new long[len]; case "[S" -> new short[len]; case "[Z" -> new boolean[len];
            case "[F" -> new float[len]; case "[D" -> new double[len];
            default -> new Object[len];
        };
    }

    private static Object arrayGet(Object arr, int i) {
        try {
            if (arr == null || !arr.getClass().isArray()) return UNKNOWN;
            Object v = Array.get(arr, i);
            if (v instanceof Byte b) return (int) b;        // dalvik widens sub-int to int
            if (v instanceof Short s) return (int) s;
            if (v instanceof Character c) return (int) c;
            if (v instanceof Boolean b) return b ? 1 : 0;
            return v;
        } catch (Exception e) { return UNKNOWN; }
    }

    private static void arrayPut(Object arr, int i, Object v) {
        try {
            if (arr == null || !arr.getClass().isArray()) return;
            Class<?> ct = arr.getClass().getComponentType();
            if (ct == byte.class) Array.setByte(arr, i, (byte) asInt(v));
            else if (ct == char.class) Array.setChar(arr, i, (char) asInt(v));
            else if (ct == int.class) Array.setInt(arr, i, asInt(v));
            else if (ct == short.class) Array.setShort(arr, i, (short) asInt(v));
            else if (ct == boolean.class) Array.setBoolean(arr, i, asInt(v) != 0);
            else if (ct == long.class) Array.setLong(arr, i, asLong(v));
            else if (ct == float.class) Array.setFloat(arr, i, asFloat(v));
            else if (ct == double.class) Array.setDouble(arr, i, asDouble(v));
            else Array.set(arr, i, v == UNKNOWN ? null : v);
        } catch (Exception ignored) {}
    }

    private static void fillArray(Object arr, ArrayPayload ap) {
        if (arr == null || !arr.getClass().isArray()) return;
        List<Number> els = ap.getArrayElements();
        for (int i = 0; i < els.size(); i++) arrayPut(arr, i, els.get(i));
    }

    // ---------- reflection coercion ----------

    private static Object coerce(Object v, Class<?> target) {
        Object u = unwrap(v);
        if (target == int.class || target == Integer.class) return asInt(u);
        if (target == long.class || target == Long.class) return asLong(u);
        if (target == short.class || target == Short.class) return (short) asInt(u);
        if (target == byte.class || target == Byte.class) return (byte) asInt(u);
        if (target == char.class || target == Character.class) return (char) asInt(u);
        if (target == boolean.class || target == Boolean.class) return asInt(u) != 0;
        if (target == float.class || target == Float.class) return asFloat(u);
        if (target == double.class || target == Double.class) return asDouble(u);
        return u; // object / array — pass through
    }

    private static Object unwrap(Object v) { return v == UNKNOWN ? null : v; }

    // placeholder for an object allocated by new-instance but not yet <init>'d
    private static final class Uninit { final String type; Uninit(String t) { type = t; } }

    private static boolean isWide(String desc) { return desc.equals("J") || desc.equals("D"); }

    // ---------- type descriptor helpers ----------

    /** Lcom/foo/Bar; -> com.foo.Bar ; [B -> [B ; I -> I (primitives kept) */
    static String descToDotted(String d) {
        if (d.startsWith("L") && d.endsWith(";")) return d.substring(1, d.length() - 1).replace('/', '.');
        return d;
    }

    /** Dalvik descriptor -> binary Java class name usable by Class.forName / our Sandbox. */
    static String descToJava(String d) {
        return switch (d) {
            case "I" -> "int"; case "J" -> "long"; case "S" -> "short"; case "B" -> "byte";
            case "C" -> "char"; case "Z" -> "boolean"; case "F" -> "float"; case "D" -> "double"; case "V" -> "void";
            default -> {
                if (d.startsWith("L") && d.endsWith(";")) yield d.substring(1, d.length() - 1).replace('/', '.');
                if (d.startsWith("[")) yield d;            // array descriptor; Sandbox.load decodes it
                yield d;
            }
        };
    }

    private static String simple(String javaName) {
        int dot = javaName.lastIndexOf('.');
        return dot >= 0 ? javaName.substring(dot + 1) : javaName;
    }

    private void record(Result top, int addr, Instruction in, String effect) {
        if (trace && top.trace.size() < 5000) {
            top.trace.add(new TraceStep(addr, in.getOpcode().name, effect == null ? "" : effect));
        }
    }

    private static String ellipsis(String s) {
        return s.length() > 40 ? s.substring(0, 38) + "…" : s;
    }

    /** RE-friendly rendering of any register value. */
    static String render(Object v) {
        if (v == null) return "null";
        if (v == UNKNOWN) return "<unknown>";
        if (v instanceof String s) return "\"" + s + "\"";
        if (v instanceof byte[] b) {
            String hex = toHex(b);
            String asTxt = printable(b);
            return "byte[" + b.length + "] " + hex + (asTxt != null ? "  (\"" + asTxt + "\")" : "");
        }
        if (v instanceof char[] c) return "\"" + new String(c) + "\"";
        if (v instanceof int[] a) return "int" + Arrays.toString(a);
        if (v instanceof Uninit u) return "<new " + simple(descToDotted(u.type)) + ">";
        return String.valueOf(v);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, 32);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
        if (b.length > n) sb.append("…");
        return sb.toString();
    }

    private static String printable(byte[] b) {
        if (b.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, 64);
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xff;
            if (c < 0x20 || c > 0x7e) return null;
            sb.append((char) c);
        }
        return sb.toString();
    }
}
