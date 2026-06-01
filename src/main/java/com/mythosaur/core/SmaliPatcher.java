package com.mythosaur.core;

import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Smali patch pipeline, all in-process where possible:
 *   baksmali (lib) → edit smali → smali (lib) → repackage APK → zipalign → apksigner → adb install
 *
 * baksmali/smali are libraries (no apktool subprocess). zipalign/apksigner/keytool/adb
 * are standard Android SDK CLIs invoked as processes (no library equivalent for v2/v3 signing).
 */
public class SmaliPatcher {

    public interface Progress { void update(String msg); }

    private final Project project;
    private final Path smaliRoot;            // workspace/smali-out
    private final Path apktoolRoot;          // workspace/apktool-out (full decode)
    private final Path buildDir;             // workspace/build
    /** dex entry name (classes.dex) -> its smali subfolder. */
    private final Map<String, Path> dexFolders = new LinkedHashMap<>();
    private boolean disassembled = false;

    public SmaliPatcher(Project project) {
        this.project = project;
        this.smaliRoot = project.getWorkspace().resolve("smali-out");
        this.apktoolRoot = project.getWorkspace().resolve("apktool-out");
        this.buildDir = project.getWorkspace().resolve("build");
    }

    public boolean isDisassembled() { return disassembled; }
    public Path getSmaliRoot() { return smaliRoot; }
    public Path getApktoolRoot() { return apktoolRoot; }

    // ---------- Full apktool decode/rebuild (resources + manifest + smali) ----------

    /** Full decode via apktool — gives editable smali + res + AndroidManifest. */
    public Path apktoolDecode(Progress p) throws Exception {
        ApktoolService.decode(project.getApkFile(), apktoolRoot.toFile(), p::update);
        return apktoolRoot;
    }

    /** apktool build → zipalign → apksigner sign → verify. Returns the signed APK. */
    public File apktoolRebuild(Progress p) throws Exception {
        if (!Files.isDirectory(apktoolRoot)) apktoolDecode(p);
        Files.createDirectories(buildDir);

        File unsigned = buildDir.resolve("apktool-unsigned.apk").toFile();
        ApktoolService.build(apktoolRoot.toFile(), unsigned, p::update);

        p.update("Aligning (zipalign)…");
        File aligned = buildDir.resolve("apktool-aligned.apk").toFile();
        run("zipalign", "-f", "4", unsigned.getAbsolutePath(), aligned.getAbsolutePath());

        File keystore = ensureDebugKeystore(p);
        p.update("Signing (apksigner)…");
        File signed = buildDir.resolve("patched-apktool.apk").toFile();
        run("apksigner", "sign", "--ks", keystore.getAbsolutePath(),
                "--ks-key-alias", "mythosaur", "--ks-pass", "pass:mythosaur",
                "--key-pass", "pass:mythosaur", "--out", signed.getAbsolutePath(),
                aligned.getAbsolutePath());

        String verify = verify(signed);
        p.update("Signature: " + verify);
        return signed;
    }

    /** Run `apksigner verify --verbose` and summarise which schemes verified. */
    public String verify(File apk) {
        try {
            String out = capture("apksigner", "verify", "--verbose", apk.getAbsolutePath());
            StringBuilder sb = new StringBuilder();
            for (String scheme : new String[]{"v1 scheme", "v2 scheme", "v3 scheme"}) {
                int i = out.indexOf(scheme);
                if (i >= 0) {
                    boolean ok = out.substring(i, Math.min(out.length(), i + 60)).contains("true");
                    sb.append(scheme.charAt(1)).append(ok ? "✓ " : "✗ ");
                }
            }
            return sb.length() > 0 ? sb.toString().trim() : "verified";
        } catch (Exception e) {
            return "verify failed: " + e.getMessage();
        }
    }

    /** baksmali every dex into its own subfolder so we can reassemble 1:1. */
    public void disassemble(Progress p) throws IOException {
        Files.createDirectories(smaliRoot);
        List<DexBackedDexFile> dexes = project.getDexFiles();
        int idx = 0;
        for (DexBackedDexFile dex : dexes) {
            String entryName = idx == 0 ? "classes.dex" : "classes" + (idx + 1) + ".dex";
            Path folder = smaliRoot.resolve("classes" + (idx == 0 ? "" : String.valueOf(idx + 1)));
            p.update("Disassembling " + entryName + "…");
            BaksmaliOptions opts = new BaksmaliOptions();
            opts.apiLevel = dex.getOpcodes().api;
            Baksmali.disassembleDexFile(dex, folder.toFile(),
                    Math.max(1, Runtime.getRuntime().availableProcessors() - 1), opts);
            dexFolders.put(entryName, folder);
            idx++;
        }
        disassembled = true;
        p.update("Disassembled " + dexes.size() + " dex file(s) to smali");
    }

    /** Assemble smali → dex(es), repackage into the APK, align, and sign. Returns the signed APK. */
    public File buildSignedApk(Progress p) throws Exception {
        if (!disassembled) disassemble(p);
        Files.createDirectories(buildDir);

        // 1) assemble each smali folder back to a dex
        Map<String, File> newDexes = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : dexFolders.entrySet()) {
            p.update("Assembling " + e.getKey() + "…");
            File outDex = buildDir.resolve(e.getKey()).toFile();
            SmaliOptions opts = new SmaliOptions();
            opts.outputDexFile = outDex.getAbsolutePath();
            opts.jobs = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            boolean ok = Smali.assemble(opts, e.getValue().toString());
            if (!ok) throw new IOException("smali assembly failed for " + e.getKey());
            newDexes.put(e.getKey(), outDex);
        }

        // 2) repackage: copy original APK, replace the dex entries, strip old signature
        p.update("Repackaging APK…");
        File unsigned = buildDir.resolve("unsigned.apk").toFile();
        repackage(project.getApkFile(), unsigned, newDexes);

        // 3) zipalign
        p.update("Aligning (zipalign)…");
        File aligned = buildDir.resolve("aligned.apk").toFile();
        run("zipalign", "-f", "4", unsigned.getAbsolutePath(), aligned.getAbsolutePath());

        // 4) sign (generate debug keystore if needed)
        File keystore = ensureDebugKeystore(p);
        p.update("Signing (apksigner)…");
        File signed = buildDir.resolve("patched.apk").toFile();
        run("apksigner", "sign",
                "--ks", keystore.getAbsolutePath(),
                "--ks-key-alias", "mythosaur",
                "--ks-pass", "pass:mythosaur",
                "--key-pass", "pass:mythosaur",
                "--out", signed.getAbsolutePath(),
                aligned.getAbsolutePath());

        String verify = verify(signed);
        p.update("Signed (" + verify + "): " + signed.getAbsolutePath());
        return signed;
    }

    public void install(String deviceSerial, File apk, Progress p) throws Exception {
        p.update("Installing via adb…");
        if (deviceSerial != null && !deviceSerial.isBlank()) {
            run("adb", "-s", deviceSerial, "install", "-r", apk.getAbsolutePath());
        } else {
            run("adb", "install", "-r", apk.getAbsolutePath());
        }
        p.update("Installed.");
    }

    // ---- helpers ----

    private void repackage(File originalApk, File out, Map<String, File> newDexes) throws IOException {
        try (ZipFile zip = new ZipFile(originalApk);
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out.toPath()))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buf = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                // skip old signature + dexes we are replacing
                if (name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA") || name.endsWith(".SF") || name.endsWith(".MF"))) {
                    continue;
                }
                if (newDexes.containsKey(name)) continue;

                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = zip.getInputStream(entry)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            // add the new dexes
            for (Map.Entry<String, File> e : newDexes.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                Files.copy(e.getValue().toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    private File ensureDebugKeystore(Progress p) throws Exception {
        File ks = project.getWorkspace().resolve("debug.keystore").toFile();
        if (ks.isFile()) return ks;
        p.update("Generating debug keystore…");
        run("keytool", "-genkeypair", "-v",
                "-keystore", ks.getAbsolutePath(),
                "-alias", "mythosaur",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                "-storepass", "mythosaur", "-keypass", "mythosaur",
                "-dname", "CN=Mythosaur,OU=RE,O=Mythosaur,L=NA,ST=NA,C=NA");
        return ks;
    }

    private void run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        try (InputStream is = proc.getInputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) out.append(new String(b, 0, n));
        }
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException(cmd[0] + " failed (" + code + "): "
                    + out.substring(0, Math.min(out.length(), 600)));
        }
    }

    /** Like run() but returns stdout and tolerates a non-zero exit (for verify). */
    private String capture(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        try (InputStream is = proc.getInputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) out.append(new String(b, 0, n));
        }
        proc.waitFor();
        return out.toString();
    }
}
