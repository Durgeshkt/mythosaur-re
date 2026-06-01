package com.mythosaur.core;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps apktool for FULL apk decode/rebuild — unlike the dexlib2/smali library
 * path (DEX only), apktool also decodes resources.arsc and the binary
 * AndroidManifest into editable text, and rebuilds them properly.
 *
 * Prefers a self-contained official apktool jar (the Kali-packaged 2.7.0 CLI has
 * a snakeyaml bug that breaks `b`). The jar is fetched once to ~/.mythosaur/tools.
 */
public final class ApktoolService {

    public interface Progress { void update(String msg); }

    private static final String APKTOOL_URL =
            "https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar";
    private static final Path JAR =
            Path.of(System.getProperty("user.home"), ".mythosaur", "tools", "apktool.jar");

    private ApktoolService() {}

    public static boolean available() {
        return Files.isRegularFile(JAR) || which("apktool");
    }

    private static synchronized void ensureJar(Progress p) throws Exception {
        if (Files.isRegularFile(JAR)) return;
        p.update("Fetching apktool.jar (first run, ~23MB)…");
        Files.createDirectories(JAR.getParent());
        try (InputStream in = new java.net.URL(APKTOOL_URL).openStream()) {
            Files.copy(in, JAR);
        }
        p.update("apktool ready.");
    }

    private static List<String> base(Progress p) throws Exception {
        ensureJar(p);
        List<String> cmd = new ArrayList<>();
        cmd.add("java"); cmd.add("-jar"); cmd.add(JAR.toString());
        return cmd;
    }

    /** Full decode (smali + res + manifest + assets). */
    public static void decode(File apk, File outDir, Progress p) throws Exception {
        p.update("apktool: decoding resources + smali…");
        List<String> cmd = base(p);
        cmd.add("d"); cmd.add("-f"); cmd.add("-o"); cmd.add(outDir.getAbsolutePath());
        cmd.add(apk.getAbsolutePath());
        run(p, cmd);
        p.update("apktool: decoded to " + outDir.getName());
    }

    /** Rebuild everything (res + dex). */
    public static void build(File dir, File outApk, Progress p) throws Exception {
        p.update("apktool: rebuilding APK…");
        List<String> cmd = base(p);
        cmd.add("b"); cmd.add("-o"); cmd.add(outApk.getAbsolutePath());
        cmd.add(dir.getAbsolutePath());
        run(p, cmd);
        p.update("apktool: built " + outApk.getName());
    }

    private static boolean which(String cmd) {
        try { return new ProcessBuilder("which", cmd).start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    private static void run(Progress p, List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        try (InputStream is = proc.getInputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) {
                String chunk = new String(b, 0, n);
                out.append(chunk);
                line.append(chunk);
                int nl;
                while ((nl = line.indexOf("\n")) >= 0) {
                    String l = line.substring(0, nl).trim();
                    if (!l.isEmpty()) p.update("apktool: " + l);
                    line.delete(0, nl + 1);
                }
            }
        }
        int code = proc.waitFor();
        if (code != 0) {
            throw new Exception("apktool failed (" + code + "): "
                    + out.substring(0, Math.min(out.length(), 800)));
        }
    }
}
