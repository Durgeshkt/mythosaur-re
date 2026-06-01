package com.mythosaur.core;

import com.googlecode.d2j.dex.Dex2jar;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bridges the gap between jadx (decompiles DEX directly) and the JVM-bytecode
 * decompilers (CFR / Vineflower / Procyon, which need {@code .class} files): converts
 * the whole APK to a JAR once via dex2jar, cached in the workspace.
 *
 * <p>The conversion is the only heavy step in the multi-decompiler path, so it is lazy
 * (built on first alternate-decompile request) and reused thereafter.
 */
public final class DexToJar {

    private DexToJar() {}

    /** Build (or reuse) the workspace's {@code alt-decompile.jar}; returns its path. */
    public static synchronized Path ensureJar(Project project) throws Exception {
        Path jar = project.getWorkspace().resolve("alt-decompile.jar");
        if (Files.isRegularFile(jar) && Files.size(jar) > 0) return jar;
        Files.deleteIfExists(jar);
        // dex2jar reads every classes*.dex from the APK and writes one JAR
        Dex2jar.from(project.getApkFile()).to(jar);
        return jar;
    }

    public static boolean isBuilt(Project project) {
        try {
            Path jar = project.getWorkspace().resolve("alt-decompile.jar");
            return Files.isRegularFile(jar) && Files.size(jar) > 0;
        } catch (Exception e) { return false; }
    }
}
