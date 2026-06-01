package com.mythosaur.core;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.MultiDexContainer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads an APK entirely through embedded libraries:
 *   - jadx (JadxDecompiler) for Java decompilation
 *   - dexlib2 (DexFileFactory) for raw DEX structural analysis
 * No external processes, no CLI output parsing.
 */
public final class ApkLoader {

    public interface Progress {
        void update(String message);
    }

    private ApkLoader() {}

    public static Project load(File apk, Progress progress) throws Exception {
        return load(apk, null, progress);
    }

    public static Project load(File apk, File mappingFile, Progress progress) throws Exception {
        if (!apk.isFile()) {
            throw new IllegalArgumentException("APK not found: " + apk);
        }

        Path workspace = createWorkspace(apk);
        // remember this project so it can be reopened from Recent / Open Workspace
        ProjectStore.record(workspace, apk, mappingFile);

        // 0) Resilient ingestion — hardened/malicious APKs corrupt the ZIP central directory so
        // strict parsers (java.util.zip.ZipFile, used by dexlib2/apktool) refuse to open them,
        // while Android installs them fine. Repair into a clean normalized.apk first; for a
        // well-formed APK this is a no-op and the original file is used unchanged.
        progress.update("Checking archive integrity…");
        ApkSanitizer.Result san = ApkSanitizer.sanitize(apk, workspace);
        File working = san.workingApk;
        if (san.repaired) {
            for (String note : san.notes) progress.update(note);
        }

        // 1) jadx — decompile (lazy: getCode() per class produces source on demand)
        progress.update("Decompiling with jadx…");
        JadxArgs args = new JadxArgs();
        args.setInputFile(working);
        args.setOutDir(workspace.resolve("jadx-out").toFile());
        args.setShowInconsistentCode(true);
        args.setSkipResources(false);
        args.setThreadsCount(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

        if (mappingFile != null && mappingFile.isFile()) {
            // apply a ProGuard/R8 (or jadx) renames mapping to restore original names
            progress.update("Applying mapping: " + mappingFile.getName());
            args.setUserRenamesMappingsPath(mappingFile.toPath());
            args.setUserRenamesMappingsMode(jadx.api.args.UserRenamesMappingsMode.READ);
        }

        JadxDecompiler jadx = new JadxDecompiler(args);
        jadx.load();
        // Trigger class list build
        int classCount = jadx.getClasses().size();
        progress.update("jadx loaded " + classCount + " classes");

        // 2) dexlib2 — parse all dex entries from the APK (multi-dex aware)
        progress.update("Parsing DEX (dexlib2)…");
        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        MultiDexContainer<? extends DexBackedDexFile> container =
                org.jf.dexlib2.DexFileFactory.loadDexContainer(working, Opcodes.getDefault());
        for (String entryName : container.getDexEntryNames()) {
            MultiDexContainer.DexEntry<? extends DexBackedDexFile> entry = container.getEntry(entryName);
            if (entry != null) {
                dexFiles.add(entry.getDexFile());
            }
        }
        progress.update("Parsed " + dexFiles.size() + " dex file(s)");

        // Downstream readers (native/entropy scan, resources, patch pipeline) get the working
        // (repaired) archive; the original is retained for hashing / display / IOCs.
        return new Project(apk, working, workspace, jadx, dexFiles);
    }

    private static Path createWorkspace(File apk) throws Exception {
        String hash = shortHash(apk);
        String name = apk.getName().replaceAll("\\.apk$", "");
        Path ws = Path.of(System.getProperty("user.home"),
                ".mythosaur", "workspaces", name + "-" + hash);
        Files.createDirectories(ws);
        return ws;
    }

    private static String shortHash(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        // Stream the file — APKs can be 100s of MB; readAllBytes would buffer the whole thing.
        try (java.io.InputStream in = new java.io.BufferedInputStream(
                java.nio.file.Files.newInputStream(f.toPath()))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(String.format("%02x", digest[i]));
        return sb.toString();
    }
}
