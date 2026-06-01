package com.mythosaur.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds native libraries (.so) inside the APK and analyzes them with the pure-Java
 * {@link ElfFile} parser. Disassembly is delegated to objdump — but the right one:
 * most Android libs are ARM/AArch64, while the host's stock {@code objdump} is usually
 * built x86-only, so a plain {@code objdump} call fails with "can't disassemble for
 * architecture UNKNOWN". We pick a disassembler that actually targets the .so's arch
 * (a GNU cross-objdump, a multi-arch objdump, or llvm-objdump) and report a clear
 * install hint when none is available.
 */
public class NativeAnalyzer {

    private final File apk;

    public NativeAnalyzer(File apk) { this.apk = apk; }

    /** A concrete tool that can disassemble a given ELF arch. */
    public record Disassembler(String exe) {}

    /** lib/<abi>/<name>.so entry names inside the APK. */
    public List<String> listSoEntries() {
        List<String> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            var en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().endsWith(".so")) result.add(e.getName());
            }
        } catch (Exception ignored) {}
        result.sort(String::compareTo);
        return result;
    }

    public byte[] readSo(String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry e = zip.getEntry(entryName);
            if (e == null) throw new IllegalArgumentException("no such entry: " + entryName);
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    public ElfFile parse(String entryName) throws Exception {
        return new ElfFile(readSo(entryName));
    }

    /** True if at least the host objdump exists (kept for backward compatibility). */
    public static boolean objdumpAvailable() { return which("objdump"); }

    // ---- arch-aware disassembler selection ----

    private static final String[] LLVM = {
        "llvm-objdump", "llvm-objdump-20", "llvm-objdump-19", "llvm-objdump-18",
        "llvm-objdump-17", "llvm-objdump-16", "llvm-objdump-15", "llvm-objdump-14"
    };

    /**
     * Choose a disassembler that targets this ELF's architecture, or {@code null} if none
     * is installed. Preference: a GNU cross-objdump for the arch, then the host objdump if
     * it was built with support for that target (native or multi-arch), then llvm-objdump
     * (which handles all the common arches).
     */
    public Disassembler pickDisassembler(ElfFile elf) {
        String arch = elf.getArch();
        List<String> cross = new ArrayList<>();
        String hostTarget = null; // bfd target substring the host objdump must list

        if (arch.startsWith("AArch64")) {
            cross.add("aarch64-linux-gnu-objdump");
            hostTarget = "aarch64";
        } else if (arch.startsWith("ARM")) {
            cross.add("arm-linux-gnueabihf-objdump");
            cross.add("arm-linux-gnueabi-objdump");
            hostTarget = "littlearm";
        } else if (arch.equals("x86-64")) {
            cross.add("x86_64-linux-gnu-objdump");
            hostTarget = "x86-64";
        } else if (arch.equals("x86")) {
            cross.add("x86_64-linux-gnu-objdump"); // also targets i386
            hostTarget = "i386";
        } else if (arch.startsWith("MIPS")) {
            cross.add("mips-linux-gnu-objdump");
            cross.add("mips64-linux-gnuabi64-objdump");
            hostTarget = "mips";
        }

        // 1) GNU cross-objdump — if present it targets this arch by construction
        for (String c : cross) if (which(c)) return new Disassembler(c);
        // 2) host objdump — only if it was built with support for this target
        final String target = hostTarget;
        if (target != null && which("objdump") && hostObjdumpTargets().stream().anyMatch(t -> t.contains(target)))
            return new Disassembler("objdump");
        // 3) llvm-objdump — arch-agnostic universal fallback
        for (String c : LLVM) if (which(c)) return new Disassembler(c);
        return null;
    }

    /** Actionable message when no disassembler can handle this arch. */
    public String installHint(ElfFile elf) {
        String a = elf.getArch();
        String pkg;
        if (a.startsWith("AArch64")) pkg = "binutils-aarch64-linux-gnu";
        else if (a.startsWith("ARM")) pkg = "binutils-arm-linux-gnueabihf";
        else pkg = "binutils-multiarch";
        return "No disassembler installed for " + a + ".\n\n"
             + "Install any one of:\n"
             + "  • sudo apt install " + pkg + "       (GNU cross-objdump for this arch)\n"
             + "  • sudo apt install binutils-multiarch    (one objdump, all arches)\n"
             + "  • sudo apt install llvm                  (llvm-objdump, all arches)";
    }

    /**
     * Disassemble a .so (writes the bytes to a temp file, runs an arch-appropriate objdump).
     * Returns the full disassembly text; optionally focused near a symbol address.
     *
     * @throws IOException if no disassembler targets this arch, or the run fails — the
     *         message carries the tool output and an install hint, never a silent stub.
     */
    public String disassemble(String entryName, long focusAddr) throws Exception {
        byte[] bytes = readSo(entryName);
        ElfFile elf = new ElfFile(bytes);
        Disassembler tool = pickDisassembler(elf);
        if (tool == null) throw new IOException(installHint(elf));

        File tmp = File.createTempFile("mythosaur-", ".so");
        tmp.deleteOnExit();
        java.nio.file.Files.write(tmp.toPath(), bytes);
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    tool.exe(), "-d", "-C", "--no-show-raw-insn", tmp.getAbsolutePath()));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out;
            try (InputStream is = proc.getInputStream()) {
                out = new String(is.readAllBytes());
            }
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException(tool.exe() + " could not disassemble this "
                        + elf.getArch() + " library (exit " + code + "):\n\n"
                        + out.trim() + "\n\n" + installHint(elf));
            }

            if (focusAddr > 0) {
                // try to jump near the function containing focusAddr
                String hex = Long.toHexString(focusAddr);
                int idx = out.indexOf("\n" + hex + ":");
                if (idx < 0) idx = out.indexOf(hex + " <");
                if (idx > 0) return out.substring(Math.max(0, idx - 1));
            }
            return out;
        } finally {
            tmp.delete();
        }
    }

    // ---- helpers ----

    /** True if {@code exe} is on PATH. */
    private static boolean which(String exe) {
        try {
            Process p = new ProcessBuilder("which", exe).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private static volatile Set<String> hostTargets;

    /** BFD target names the host objdump was built with (e.g. elf64-x86-64), parsed once. */
    private static Set<String> hostObjdumpTargets() {
        Set<String> cached = hostTargets;
        if (cached != null) return cached;
        Set<String> targets = new HashSet<>();
        try {
            Process p = new ProcessBuilder("objdump", "--info").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.startsWith("elf") || t.startsWith("pe") || t.startsWith("mach-o")) {
                        targets.add(t.split("\\s+")[0]);
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}
        hostTargets = targets;
        return targets;
    }
}
