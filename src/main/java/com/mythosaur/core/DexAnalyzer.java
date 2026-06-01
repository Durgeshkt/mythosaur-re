package com.mythosaur.core;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodParameter;
import org.jf.dexlib2.iface.reference.StringReference;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Structural analysis from dexlib2 — classes, methods, fields, strings.
 * This is the "Cutter-like" data: accurate, typed, straight from the DEX binary.
 */
public class DexAnalyzer {

    public static final class ClassInfo {
        public final String name;        // dotted: com.foo.Bar
        public final String superName;   // dotted
        public final String accessFlags; // "public final"
        public final int methodCount;
        public final int fieldCount;
        public ClassInfo(String name, String superName, String accessFlags, int methodCount, int fieldCount) {
            this.name = name; this.superName = superName; this.accessFlags = accessFlags;
            this.methodCount = methodCount; this.fieldCount = fieldCount;
        }
    }

    public static final class MethodInfo {
        public final String className;   // dotted
        public final String name;
        public final String returnType;  // dotted/simple
        public final List<String> params;
        public final String accessFlags;
        public MethodInfo(String className, String name, String returnType, List<String> params, String accessFlags) {
            this.className = className; this.name = name; this.returnType = returnType;
            this.params = params; this.accessFlags = accessFlags;
        }
        public String signature() {
            return className + "." + name + "(" + String.join(", ", params) + ")";
        }
    }

    private final List<ClassInfo> classes = new ArrayList<>();
    private final List<MethodInfo> methods = new ArrayList<>();
    private final List<MethodInfo> imports = new ArrayList<>();
    private final TreeSet<String> strings = new TreeSet<>();

    private int totalClasses, totalMethods;

    public DexAnalyzer(List<DexBackedDexFile> dexFiles) {
        for (DexBackedDexFile dex : dexFiles) {
            analyze(dex);
        }
        // strings: pull from each dex's string pool
        for (DexBackedDexFile dex : dexFiles) {
            for (String s : dex.getStringSection()) {
                if (s != null && s.length() >= 3) strings.add(s);
            }
        }
        classes.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        methods.sort((a, b) -> a.signature().compareToIgnoreCase(b.signature()));
    }

    private void analyze(DexBackedDexFile dex) {
        for (ClassDef cls : dex.getClasses()) {
            totalClasses++;
            String className = descToDotted(cls.getType());
            String superName = cls.getSuperclass() != null ? descToDotted(cls.getSuperclass()) : "";
            String flags = AccessFlags.formatAccessFlagsForClass(cls.getAccessFlags());

            int mCount = 0, fCount = 0;
            for (Field f : cls.getFields()) fCount++;
            for (Method m : cls.getMethods()) {
                mCount++;
                totalMethods++;
                List<String> params = new ArrayList<>();
                for (MethodParameter p : m.getParameters()) {
                    params.add(simpleType(descToDotted(p.getType())));
                }
                MethodInfo mi = new MethodInfo(
                        className, m.getName(),
                        simpleType(descToDotted(m.getReturnType())),
                        params,
                        AccessFlags.formatAccessFlagsForMethod(m.getAccessFlags()));
                methods.add(mi);
                if (isFramework(className)) imports.add(mi);
            }
            classes.add(new ClassInfo(className, superName, flags, mCount, fCount));
        }
    }

    private static boolean isFramework(String cls) {
        return cls.startsWith("android.") || cls.startsWith("java.")
                || cls.startsWith("javax.") || cls.startsWith("kotlin.")
                || cls.startsWith("androidx.") || cls.startsWith("com.google.");
    }

    /** Lcom/foo/Bar; -> com.foo.Bar ; primitives mapped too. */
    public static String descToDotted(String desc) {
        if (desc == null || desc.isEmpty()) return desc;
        switch (desc.charAt(0)) {
            case 'V': return "void";
            case 'Z': return "boolean";
            case 'B': return "byte";
            case 'S': return "short";
            case 'C': return "char";
            case 'I': return "int";
            case 'J': return "long";
            case 'F': return "float";
            case 'D': return "double";
            case '[': return descToDotted(desc.substring(1)) + "[]";
            case 'L':
                String inner = desc.substring(1, desc.endsWith(";") ? desc.length() - 1 : desc.length());
                return inner.replace('/', '.');
            default: return desc;
        }
    }

    /** com.foo.Bar -> Bar (for compact display). */
    public static String simpleType(String dotted) {
        int arr = dotted.indexOf('[');
        String base = arr >= 0 ? dotted.substring(0, arr) : dotted;
        String suffix = arr >= 0 ? dotted.substring(arr) : "";
        int dot = base.lastIndexOf('.');
        return (dot >= 0 ? base.substring(dot + 1) : base) + suffix;
    }

    public List<ClassInfo> getClasses() { return classes; }
    public List<MethodInfo> getMethods() { return methods; }
    public List<MethodInfo> getImports() { return imports; }
    public List<String> getStrings() { return new ArrayList<>(strings); }
    public int getTotalClasses() { return totalClasses; }
    public int getTotalMethods() { return totalMethods; }
    public int getTotalStrings() { return strings.size(); }
}
