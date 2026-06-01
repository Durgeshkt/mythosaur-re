package com.mythosaur.core;

import org.jf.dexlib2.ReferenceType;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a call graph from dexlib2 instructions: for each method, which methods
 * it calls (callees) and which methods call it (callers). Built once, in-memory.
 */
public class XrefEngine {

    /** A method identity: com.foo.Bar.method */
    public static String key(String dottedClass, String method) {
        return dottedClass + "." + method;
    }

    public static final class Call {
        public final String fromClass, fromMethod, toClass, toMethod;
        public Call(String fc, String fm, String tc, String tm) {
            fromClass = fc; fromMethod = fm; toClass = tc; toMethod = tm;
        }
    }

    private final Map<String, List<Call>> callersOf = new HashMap<>();  // callee key -> calls into it
    private final Map<String, List<Call>> calleesOf = new HashMap<>();  // caller key -> calls out of it
    private boolean built = false;

    public XrefEngine(List<DexBackedDexFile> dexFiles) {
        this.dexFiles = dexFiles;
    }

    private final List<DexBackedDexFile> dexFiles;

    private synchronized void ensureBuilt() {
        if (built) return;
        for (DexBackedDexFile dex : dexFiles) {
            for (ClassDef cls : dex.getClasses()) {
                String fromClass = DexAnalyzer.descToDotted(cls.getType());
                for (Method m : cls.getMethods()) {
                    MethodImplementation impl = m.getImplementation();
                    if (impl == null) continue;
                    String fromMethod = m.getName();
                    for (Instruction insn : impl.getInstructions()) {
                        if (!(insn instanceof ReferenceInstruction)) continue;
                        ReferenceInstruction ri = (ReferenceInstruction) insn;
                        if (ri.getReferenceType() != ReferenceType.METHOD) continue;
                        Reference ref = ri.getReference();
                        if (!(ref instanceof MethodReference)) continue;
                        MethodReference mref = (MethodReference) ref;
                        String toClass = DexAnalyzer.descToDotted(mref.getDefiningClass());
                        String toMethod = mref.getName();

                        Call call = new Call(fromClass, fromMethod, toClass, toMethod);
                        callersOf.computeIfAbsent(key(toClass, toMethod), k -> new ArrayList<>()).add(call);
                        calleesOf.computeIfAbsent(key(fromClass, fromMethod), k -> new ArrayList<>()).add(call);
                    }
                }
            }
        }
        built = true;
    }

    /** Methods that call className.methodName. */
    public List<Call> callers(String className, String methodName) {
        ensureBuilt();
        return callersOf.getOrDefault(key(className, methodName), List.of());
    }

    /** Methods that className.methodName calls. */
    public List<Call> callees(String className, String methodName) {
        ensureBuilt();
        return calleesOf.getOrDefault(key(className, methodName), List.of());
    }
}
