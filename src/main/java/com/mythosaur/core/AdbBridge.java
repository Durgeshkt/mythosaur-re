package com.mythosaur.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the {@code adb} CLI for the JDWP debugger (Phase B).
 *
 * <p>This is the only "device" dependency in the whole tool — it is entirely optional
 * and isolated here. Everything degrades gracefully when adb (or a device) is absent:
 * {@link #available()} is false and the UI explains how to enable it. No traffic, no
 * instrumentation — just the standard install / list-process / forward-JDWP plumbing
 * that Android Studio itself uses to attach a debugger to a debuggable app.
 */
public class AdbBridge {

    private String adb;            // resolved adb path, or null
    private String serial;         // selected device serial, or null = the only/default device

    public AdbBridge() { this.adb = locateAdb(); }

    public boolean available() { return adb != null; }
    public String adbPath() { return adb; }
    public void setAdbPath(String p) { this.adb = (p != null && new File(p).canExecute()) ? p : this.adb; }
    public void setSerial(String s) { this.serial = s; }
    public String getSerial() { return serial; }

    public record Device(String serial, String description) {
        public String toString() { return serial + (description.isBlank() ? "" : "  (" + description + ")"); }
    }
    public record Proc(int pid, String pkg) {
        public String toString() { return pid + "  " + pkg; }
    }

    /** Find adb on PATH or in the usual Android SDK locations. */
    private static String locateAdb() {
        // 1) PATH
        String onPath = which("adb");
        if (onPath != null) return onPath;
        // 2) common SDK locations
        List<String> candidates = new ArrayList<>();
        for (String env : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String v = System.getenv(env);
            if (v != null) candidates.add(v + "/platform-tools/adb");
        }
        String home = System.getProperty("user.home");
        candidates.add(home + "/Android/Sdk/platform-tools/adb");
        candidates.add(home + "/Library/Android/sdk/platform-tools/adb");
        candidates.add("/opt/android-sdk/platform-tools/adb");
        candidates.add("/usr/lib/android-sdk/platform-tools/adb");
        for (String c : candidates) if (new File(c).canExecute()) return c;
        return null;
    }

    private static String which(String tool) {
        try {
            Process p = new ProcessBuilder("which", tool).redirectErrorStream(true).start();
            String out = readAll(p).trim();
            p.waitFor(3, TimeUnit.SECONDS);
            return (p.exitValue() == 0 && !out.isEmpty() && new File(out.lines().findFirst().orElse("")).canExecute())
                    ? out.lines().findFirst().get() : null;
        } catch (Exception e) { return null; }
    }

    /** Connected devices/emulators (only those in "device" state). */
    public List<Device> devices() {
        List<Device> out = new ArrayList<>();
        if (adb == null) return out;
        for (String line : run(3, adb, "devices", "-l")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices")) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length >= 2 && parts[1].startsWith("device")) {
                String desc = "";
                int m = line.indexOf("model:");
                if (m >= 0) desc = line.substring(m + 6).split("\\s+")[0].replace('_', ' ');
                out.add(new Device(parts[0], desc));
            }
        }
        return out;
    }

    /** Debuggable processes on the device (pid + package), best-effort. */
    public List<Proc> debuggableProcesses() {
        List<Proc> out = new ArrayList<>();
        if (adb == null) return out;
        // `adb jdwp` lists pids of debuggable VMs; it streams and never exits, so read briefly.
        List<String> pids = runBrief(adb_args("jdwp"), 1500);
        // map each pid -> package name via /proc/<pid>/cmdline
        for (String line : pids) {
            line = line.trim();
            if (!line.matches("\\d+")) continue;
            int pid = Integer.parseInt(line);
            String pkg = pkgOf(pid);
            out.add(new Proc(pid, pkg));
        }
        return out;
    }

    private String pkgOf(int pid) {
        List<String> r = run(3, adb_args("shell", "cat", "/proc/" + pid + "/cmdline"));
        if (!r.isEmpty()) {
            String s = r.get(0).replace('\0', ' ').trim();
            if (!s.isEmpty()) return s.split("\\s+")[0];
        }
        return "?";
    }

    /** pid of a running package (or -1). */
    public int pidOf(String pkg) {
        List<String> r = run(3, adb_args("shell", "pidof", pkg));
        for (String line : r) {
            String t = line.trim();
            if (t.matches("\\d+.*")) return Integer.parseInt(t.split("\\s+")[0]);
        }
        return -1;
    }

    /** Forward a fresh local TCP port to the app process's JDWP channel; returns the port. */
    public int forwardJdwp(int pid) {
        List<String> r = run(5, adb_args("forward", "tcp:0", "jdwp:" + pid));
        for (String line : r) {
            String t = line.trim();
            if (t.matches("\\d+")) return Integer.parseInt(t);
        }
        throw new IllegalStateException("adb forward failed for pid " + pid
                + (r.isEmpty() ? "" : ": " + String.join(" ", r)));
    }

    public void removeForward(int port) { run(3, adb_args("forward", "--remove", "tcp:" + port)); }

    /** Install (replace) an APK; returns adb's combined output. */
    public String install(File apk) {
        List<String> r = run(180, adb_args("install", "-r", "-t", apk.getAbsolutePath()));
        return String.join("\n", r);
    }

    /** Launch an explicit component, e.g. "com.app/.MainActivity". */
    public String launch(String component) {
        return String.join("\n", run(15, adb_args("shell", "am", "start", "-n", component)));
    }

    /** Launch and immediately wait for the debugger (so we can attach before app code runs). */
    public String launchWaitForDebugger(String component) {
        return String.join("\n", run(15, adb_args("shell", "am", "start", "-D", "-n", component)));
    }

    // ---------- process plumbing ----------

    private String[] adb_args(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(adb);
        if (serial != null) { cmd.add("-s"); cmd.add(serial); }
        for (String a : args) cmd.add(a);
        return cmd.toArray(new String[0]);
    }

    private List<String> run(int timeoutSec, String... cmd) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) out.add(l);
            }
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            out.add("error: " + e.getMessage());
        }
        return out;
    }

    /** Run a streaming command (e.g. `adb jdwp`) and collect output for a brief window, then kill. */
    private List<String> runBrief(String[] cmd, long millis) {
        List<String> out = new ArrayList<>();
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process proc = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) synchronized (out) { out.add(l); }
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();
            reader.join(millis);
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroyForcibly();
        }
        synchronized (out) { return new ArrayList<>(out); }
    }

    private static String readAll(Process p) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
        } catch (Exception ignored) {}
        return sb.toString();
    }
}
