package com.mythosaur.core;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A loaded APK project. Holds the live jadx decompiler handle (for Java source on
 * demand) and the parsed dexlib2 dex files (for fast structural analysis).
 * Everything is in-memory objects — no CLI, no text parsing.
 */
public class Project {

    private final File originalApk;   // the file the user opened (for hashing / display / IOCs)
    private final File apkFile;       // the working archive readers should use (repaired if needed)
    private final Path workspace;
    private final JadxDecompiler jadx;
    private final List<DexBackedDexFile> dexFiles;

    /** full class name (com.foo.Bar) -> jadx JavaClass, for navigation. */
    private final Map<String, JavaClass> classesByName = new HashMap<>();

    /** Lazily-built analysis + xref data (steps 4-5). */
    private DexAnalyzer analyzer;
    private XrefEngine xrefEngine;
    private ManifestParser manifest;
    private FlowAnalyzer flowAnalyzer;
    private SmaliPatcher smaliPatcher;
    private NativeAnalyzer nativeAnalyzer;
    private PermissionAnalyzer permissionAnalyzer;

    public Project(File originalApk, File apkFile, Path workspace, JadxDecompiler jadx,
                   List<DexBackedDexFile> dexFiles) {
        this.originalApk = originalApk;
        this.apkFile = apkFile;
        this.workspace = workspace;
        this.jadx = jadx;
        this.dexFiles = dexFiles;
        for (JavaClass cls : jadx.getClasses()) {
            classesByName.put(cls.getFullName(), cls);
        }
    }

    /** The working archive — always a clean, Java-readable ZIP (repaired from the original if it
     *  was malformed). Use this for every {@code java.util.zip} reader and the patch pipeline. */
    public File getApkFile() { return apkFile; }

    /** The original file the user opened. Use for hashing / IOCs / display, not for ZIP reads. */
    public File getOriginalApkFile() { return originalApk; }
    public Path getWorkspace() { return workspace; }
    public JadxDecompiler getJadx() { return jadx; }
    public List<DexBackedDexFile> getDexFiles() { return dexFiles; }
    public List<JavaClass> getClasses() { return jadx.getClasses(); }

    /** Find the dexlib2 Method (first overload) for a dotted class + method name. */
    public Method findMethod(String dottedClass, String methodName) {
        String desc = "L" + dottedClass.replace('.', '/') + ";";
        for (DexBackedDexFile dex : dexFiles) {
            for (ClassDef cls : dex.getClasses()) {
                if (!cls.getType().equals(desc)) continue;
                for (Method m : cls.getMethods()) {
                    if (m.getName().equals(methodName) && m.getImplementation() != null) {
                        return m;
                    }
                }
            }
        }
        return null;
    }

    public JavaClass findClass(String fullName) {
        JavaClass c = classesByName.get(fullName);
        if (c != null) return c;
        // Try without inner-class suffix
        int dollar = fullName.indexOf('$');
        if (dollar > 0) return classesByName.get(fullName.substring(0, dollar));
        return null;
    }

    // The lazy analyzers below are reached from several background workers at once
    // (protections, app-flow, CFG) plus the EDT, so the lazy-init must be synchronized
    // to avoid double-construction and unsafe publication of half-built objects.

    public synchronized DexAnalyzer getAnalyzer() {
        if (analyzer == null) analyzer = new DexAnalyzer(dexFiles);
        return analyzer;
    }

    public synchronized XrefEngine getXrefEngine() {
        if (xrefEngine == null) xrefEngine = new XrefEngine(dexFiles);
        return xrefEngine;
    }

    public synchronized ManifestParser getManifest() {
        if (manifest == null) manifest = new ManifestParser(jadx, workspace, apkFile);
        return manifest;
    }

    public synchronized FlowAnalyzer getFlowAnalyzer() {
        if (flowAnalyzer == null) flowAnalyzer = new FlowAnalyzer(getManifest(), dexFiles);
        return flowAnalyzer;
    }

    public synchronized SmaliPatcher getSmaliPatcher() {
        if (smaliPatcher == null) smaliPatcher = new SmaliPatcher(this);
        return smaliPatcher;
    }

    public synchronized NativeAnalyzer getNativeAnalyzer() {
        if (nativeAnalyzer == null) nativeAnalyzer = new NativeAnalyzer(apkFile);
        return nativeAnalyzer;
    }

    public synchronized PermissionAnalyzer getPermissionAnalyzer() {
        if (permissionAnalyzer == null) permissionAnalyzer = new PermissionAnalyzer(this);
        return permissionAnalyzer;
    }

    public String getName() {
        String n = originalApk.getName();
        return n.endsWith(".apk") ? n.substring(0, n.length() - 4) : n;
    }

    public void close() {
        try { jadx.close(); } catch (Exception ignored) {}
    }
}
