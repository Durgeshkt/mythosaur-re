package com.mythosaur.core;

import java.util.Set;

/**
 * The security boundary for {@link DalvikInterpreter}'s reflective leaf calls.
 *
 * <p>The interpreter never loads the analysed APK's own classes — they are emulated.
 * It only dispatches calls into a curated set of <i>standard JDK</i> classes (the kind
 * a string-decryptor actually uses). This class decides what is allowed to run for real.
 *
 * <p>Default-deny: a class must be under an allowed package prefix AND not on the
 * blocklist; specific dangerous methods (process exec, native load, exit, file/socket
 * open) are additionally refused. This keeps untrusted decryptor logic from doing
 * anything beyond pure computation while we "dry run" it.
 */
final class Sandbox {

    static final class Blocked extends RuntimeException {
        Blocked(String m) { super(m); }
    }

    // Packages whose classes are pure-computation enough to run for real.
    private static final String[] ALLOWED_PREFIXES = {
            "java.lang.",          // String, StringBuilder, Math, Integer, Character, … (filtered below)
            "java.util.",          // Arrays, List, Map, Base64, zip, regex helpers
            "java.math.",          // BigInteger, BigDecimal
            "java.text.",          // formatters
            "java.nio.charset.",   // Charset
            "java.nio.ByteBuffer", // (prefix match) buffers
            "java.security.MessageDigest",
            "java.security.spec.",
            "javax.crypto.",       // Cipher, Mac, SecretKeyFactory
    };

    // Classes that are dangerous even though they sit under an allowed prefix.
    private static final Set<String> BLOCKED_CLASSES = Set.of(
            "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process",
            "java.lang.Thread", "java.lang.ThreadGroup",
            "java.lang.ClassLoader", "java.lang.Class", "java.lang.Module",
            "java.lang.SecurityManager"
    );

    // Method names refused on otherwise-allowed classes (e.g. System).
    private static final Set<String> BLOCKED_METHODS = Set.of(
            "exit", "halt", "load", "loadLibrary", "exec", "gc",
            "getRuntime", "setSecurityManager", "setProperty", "clearProperty",
            "forName", "newInstance", "defineClass"
    );

    private Sandbox() {}

    /** Is this Java class name something we are willing to load + call into? */
    static boolean isLoadable(String javaName) {
        String cls = arrayElement(javaName);
        if (isPrimitive(cls)) return true;
        if (BLOCKED_CLASSES.contains(cls)) return false;
        for (String p : ALLOWED_PREFIXES) {
            if (cls.startsWith(p) || cls.equals(trimTrailingDot(p))) return true;
        }
        return false;
    }

    /** Resolve a Java name (incl. arrays / primitives) to a Class, enforcing the policy. */
    static Class<?> load(String javaName) throws ClassNotFoundException {
        switch (javaName) {
            case "int": return int.class;     case "long": return long.class;
            case "short": return short.class; case "byte": return byte.class;
            case "char": return char.class;   case "boolean": return boolean.class;
            case "float": return float.class; case "double": return double.class;
            case "void": return void.class;
        }
        if (javaName.startsWith("[")) {
            // array descriptor like [B or [Ljava/lang/String;
            return Class.forName(javaName.replace('/', '.'));
        }
        if (!isLoadable(javaName)) throw new Blocked(javaName);
        return Class.forName(javaName);
    }

    static void checkConstruct(Class<?> cls) {
        if (BLOCKED_CLASSES.contains(cls.getName())) throw new Blocked(cls.getName());
    }

    static void checkMethod(Class<?> cls, String method) {
        if (BLOCKED_CLASSES.contains(cls.getName())) throw new Blocked(cls.getName() + "." + method);
        if (BLOCKED_METHODS.contains(method)) throw new Blocked(cls.getName() + "." + method);
    }

    private static String arrayElement(String name) {
        // [Ljava.lang.String; -> java.lang.String ; [B -> byte
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') dims++;
        if (dims == 0) return name;
        String el = name.substring(dims);
        if (el.startsWith("L") && el.endsWith(";")) return el.substring(1, el.length() - 1).replace('/', '.');
        return switch (el) {
            case "B" -> "byte"; case "C" -> "char"; case "I" -> "int"; case "J" -> "long";
            case "S" -> "short"; case "Z" -> "boolean"; case "F" -> "float"; case "D" -> "double";
            default -> el;
        };
    }

    private static boolean isPrimitive(String n) {
        return switch (n) {
            case "int", "long", "short", "byte", "char", "boolean", "float", "double", "void" -> true;
            default -> false;
        };
    }

    private static String trimTrailingDot(String p) {
        return p.endsWith(".") ? p.substring(0, p.length() - 1) : p;
    }
}
