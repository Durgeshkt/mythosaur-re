package com.mythosaur.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/**
 * Persists enough about each opened APK so a project can be <b>reopened</b> later —
 * both from a "Recent Projects" list and by picking the workspace folder directly.
 *
 * <p>Each workspace ({@code ~/.mythosaur/workspaces/<name>-<hash>/}) gets a
 * {@code mythosaur.project} descriptor (original APK path, optional mapping, name,
 * timestamp). A global {@code ~/.mythosaur/recent.txt} lists workspaces, newest first.
 * The decompile output, smali edits and patched APKs already live in the workspace, so
 * reopening restores the whole working state — not just the file picker location.
 */
public final class ProjectStore {

    private static final String DESCRIPTOR = "mythosaur.project";
    private static final int MAX_RECENT = 15;

    private ProjectStore() {}

    /** A reopenable project entry. */
    public record Entry(Path workspace, String name, File apk, File mapping, long opened) {
        public boolean apkExists() { return apk != null && apk.isFile(); }
    }

    private static Path root() { return Path.of(System.getProperty("user.home"), ".mythosaur"); }
    private static Path recentFile() { return root().resolve("recent.txt"); }

    /** Write/refresh a workspace descriptor and push it to the top of the recent list. */
    public static void record(Path workspace, File apk, File mapping) {
        try {
            Properties p = new Properties();
            p.setProperty("apk", apk.getAbsolutePath());
            p.setProperty("name", apk.getName().replaceAll("\\.apk$", ""));
            if (mapping != null) p.setProperty("mapping", mapping.getAbsolutePath());
            p.setProperty("opened", Long.toString(System.currentTimeMillis()));
            try (var out = Files.newOutputStream(workspace.resolve(DESCRIPTOR))) {
                p.store(out, "Mythosaur RE project descriptor");
            }
            pushRecent(workspace);
        } catch (IOException ignored) {
            // a missing descriptor only costs the convenience of reopening; not fatal
        }
    }

    /** Read a workspace descriptor, or null if it isn't a Mythosaur workspace. */
    public static Entry read(Path workspace) {
        Path desc = workspace.resolve(DESCRIPTOR);
        if (!Files.isRegularFile(desc)) return null;
        try (var in = Files.newInputStream(desc)) {
            Properties p = new Properties();
            p.load(in);
            String apk = p.getProperty("apk");
            if (apk == null) return null;
            String map = p.getProperty("mapping");
            return new Entry(
                    workspace,
                    p.getProperty("name", workspace.getFileName().toString()),
                    new File(apk),
                    map != null ? new File(map) : null,
                    Long.parseLong(p.getProperty("opened", "0")));
        } catch (Exception e) {
            return null;
        }
    }

    /** Recent projects, newest first, skipping any whose workspace has vanished. */
    public static List<Entry> recent() {
        List<Entry> out = new ArrayList<>();
        for (String line : readRecentLines()) {
            Path ws = Path.of(line);
            if (!Files.isDirectory(ws)) continue;
            Entry e = read(ws);
            if (e != null) out.add(e);
        }
        return out;
    }

    // ---------- recent.txt plumbing ----------

    private static void pushRecent(Path workspace) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        lines.add(workspace.toAbsolutePath().toString());     // newest first
        lines.addAll(readRecentLines());
        List<String> capped = lines.stream().limit(MAX_RECENT).toList();
        try {
            Files.createDirectories(root());
            Files.write(recentFile(), capped, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private static List<String> readRecentLines() {
        try {
            if (Files.isRegularFile(recentFile())) return Files.readAllLines(recentFile(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
        return List.of();
    }
}
