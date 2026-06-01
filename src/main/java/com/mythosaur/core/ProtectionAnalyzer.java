package com.mythosaur.core;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Detects how an APK is protected, for serious RE:
 *  - Packers / DEX wrappers (Bangcle, Jiagu, Tencent Legu, …) — the visible DEX is a stub;
 *    the real code is encrypted and unpacked at runtime.
 *  - Obfuscation level + likely obfuscator.
 *  - Anti-RE checks baked in (Frida / root / debugger / emulator detection).
 *  - Encrypted payloads hidden in the APK (high Shannon entropy ⇒ packed/encrypted DEX).
 *
 * Static analysis can DETECT and LOCATE these; it cannot generically unpack a runtime
 * packer (that needs a dynamic memory dump). This panel tells the analyst what they're
 * up against and where the hidden payload is.
 */
public class ProtectionAnalyzer {

    public static final class Report {
        public boolean packed;
        public String packerName = "Not detected";
        public String packerConfidence = "none";
        public final List<String> packerIndicators = new ArrayList<>();

        /** runtime DEX loading / encryption — i.e. the real code is loaded/decrypted at run time. */
        public boolean dynamicDexLoading;
        public final List<String> dynamicLoadingEvidence = new ArrayList<>();

        public int obfuscationPercent;
        public String obfuscator = "Unknown / none";
        public final List<String> obfuscationIndicators = new ArrayList<>();

        public final List<String> antiFrida = new ArrayList<>();
        public final List<String> antiRoot = new ArrayList<>();
        public final List<String> antiDebug = new ArrayList<>();
        public final List<String> antiEmulator = new ArrayList<>();

        public final List<EncryptedFile> encryptedPayloads = new ArrayList<>();
    }

    public static final class EncryptedFile {
        public final String name; public final double entropy; public final long size; public final String note;
        EncryptedFile(String name, double entropy, long size, String note) {
            this.name = name; this.entropy = entropy; this.size = size; this.note = note;
        }
    }

    private static final class Packer {
        final String name; final String[] soPatterns; final String[] classPatterns;
        Packer(String name, String[] so, String[] cls) { this.name = name; soPatterns = so; classPatterns = cls; }
    }

    private static final Packer[] PACKERS = {
        new Packer("Bangcle / SecNeo", new String[]{"libsecexe","libsecmain","libSecShell","libDexHelper","libsecpreload"}, new String[]{"com.secneo","com.SecShell"}),
        new Packer("Qihoo 360 Jiagu", new String[]{"libjiagu","libprotectClass"}, new String[]{"com.qihoo.util","com.qihoo360","com.stub.StubApp"}),
        new Packer("Tencent Legu", new String[]{"libshell-super","libshella","libtxf","libBugly"}, new String[]{"com.tencent.StubShell","com.tencent.bugly"}),
        new Packer("Baidu", new String[]{"libbaiduprotect"}, new String[]{"com.baidu.protect"}),
        new Packer("Alibaba (Ali)", new String[]{"libmobisec","libsgmain","libsgsecuritybody"}, new String[]{"com.alibaba.wireless.security","com.ali.mobisecenhance"}),
        new Packer("DexProtector", new String[]{"libdexprotector"}, new String[]{"com.licel"}),
        new Packer("Ijiami", new String[]{"libexec","libexecmain"}, new String[]{"com.shell.NativeApplication","com.shell.SuperApplication"}),
        new Packer("Naga / Nagain", new String[]{"libchaosvmp","libnagavm","libddog"}, new String[]{}),
        new Packer("APKProtect", new String[]{"libAPKProtect"}, new String[]{}),
        new Packer("AppSealing", new String[]{"libcovault","libcaa"}, new String[]{"com.inka.appsealing"}),
        new Packer("Promon SHIELD", new String[]{"libshield"}, new String[]{"no.promon"}),
        new Packer("Whitecryption", new String[]{"libwc"}, new String[]{"com.whitecryption"}),
        // "np_protect" shell (often wrapping a Virbox-protected payload) — seen on Indian
        // wedding-invitation / fake-support banking-stealer APKs. The real DEX is encrypted in
        // assets and a ShellApplication stub loads it via the (deliberately mis-named) .so libs.
        new Packer("NP-Protect / Virbox shell", new String[]{"libnp_protect", "libapp.manage.protect", "libgoogle.play.protect"},
                new String[]{"np.protect", "android.support.dexpro"})
    };

    // anti-RE signature strings
    private static final String[] FRIDA = {"frida","gum-js-loop","gmain","linjector","re.frida","frida-server","frida-agent","27042","gadget"};
    private static final String[] ROOT  = {"rootbeer","magisk","/system/xbin/su","/sbin/su","/system/bin/su","superuser","busybox","test-keys","/su/bin","eu.chainfire"};
    private static final String[] DEBUG = {"isdebuggerconnected","tracerpid","ptrace","android_server","/proc/self/status","waitfordebugger"};
    private static final String[] EMU   = {"goldfish","ranchu","ro.kernel.qemu","generic_x86","vbox","genymotion","/dev/qemu","sys.vbox","nox","bluestacks"};

    public static Report analyze(Project project) {
        Report r = new Report();

        // gather identifiers
        Set<String> soNames = new LinkedHashSet<>();
        for (String so : project.getNativeAnalyzer().listSoEntries()) {
            String base = so.substring(so.lastIndexOf('/') + 1);
            soNames.add(base.toLowerCase());
        }
        DexAnalyzer dex = project.getAnalyzer();
        List<String> classNames = new ArrayList<>();
        for (DexAnalyzer.ClassInfo c : dex.getClasses()) classNames.add(c.name);

        detectDynamicLoading(r, project);   // sets dynamicDexLoading
        detectPacker(r, soNames, classNames, project);
        detectObfuscation(r, classNames);
        detectAntiRE(r, project, dex, soNames);
        scanEntropy(r, project);

        return r;
    }

    /** Detect runtime DEX loading / decryption — the real signal that an app's code is
     *  wrapped (loaded/decrypted at run time) rather than directly in classes.dex. The
     *  referenced type descriptors live in the DEX string pool, so a cheap scan finds them. */
    private static void detectDynamicLoading(Report r, Project project) {
        String[] loaders = {
            "Ldalvik/system/DexClassLoader;",
            "Ldalvik/system/InMemoryDexClassLoader;",
            "Ldalvik/system/BaseDexClassLoader;",
            "Ldalvik/system/DexFile;",
            "Ldalvik/system/PathClassLoader;"
        };
        boolean crypto = false, loader = false;
        Set<String> foundLoaders = new LinkedHashSet<>();
        for (var dex : project.getDexFiles()) {
            for (String s : dex.getStringSection()) {
                if (s == null) continue;
                for (String l : loaders) {
                    if (s.equals(l)) {
                        foundLoaders.add(l.substring(l.lastIndexOf('/') + 1).replace(";", ""));
                        if (!l.contains("PathClassLoader")) loader = true; // PathClassLoader alone is normal
                    }
                }
                if (s.contains("javax/crypto") || s.equals("AES") || s.contains("Cipher")) crypto = true;
            }
        }
        if (loader) {
            r.dynamicDexLoading = true;
            r.dynamicLoadingEvidence.add("Uses " + String.join(", ", foundLoaders)
                    + " — code is loaded at runtime, not all in classes.dex");
            if (crypto) r.dynamicLoadingEvidence.add("Crypto APIs present — runtime-loaded DEX is likely encrypted");
        }
    }

    private static void detectPacker(Report r, Set<String> soNames, List<String> classNames, Project project) {
        String appClass = project.getManifest().getApplicationClass();
        for (Packer p : PACKERS) {
            int hits = 0;
            for (String pat : p.soPatterns) {
                for (String so : soNames) if (so.contains(pat.toLowerCase())) { r.packerIndicators.add("native lib: " + so + " (" + p.name + ")"); hits++; break; }
            }
            for (String pat : p.classPatterns) {
                for (String cn : classNames) if (cn.startsWith(pat)) { r.packerIndicators.add("loader class: " + cn); hits++; break; }
                if (appClass != null && appClass.startsWith(pat)) { r.packerIndicators.add("Application class: " + appClass); hits++; }
            }
            if (hits >= 2) { r.packed = true; r.packerName = p.name; r.packerConfidence = "high"; return; }
            if (hits == 1 && !r.packed) { r.packed = true; r.packerName = p.name; r.packerConfidence = "medium"; }
        }
        // heuristic: tiny stub dex + custom Application + native libs ⇒ unknown packer
        if (!r.packed && classNames.size() < 60 && !soNames.isEmpty() && appClass != null) {
            r.packed = true; r.packerName = "Unknown packer"; r.packerConfidence = "low";
            r.packerIndicators.add("Very few classes (" + classNames.size() + ") + custom Application + native libs");
        }
        // No classic packer signature, but the app loads/decrypts code at runtime — this is
        // a DEX-wrapper / RASP protector (e.g. DexGuard, Promon) that obfuscates everything,
        // so it has no distinctive .so or class name to match.
        if (!r.packed && r.dynamicDexLoading) {
            r.packed = true;
            r.packerName = "Runtime DEX wrapper / RASP (DexGuard-style)";
            r.packerConfidence = "medium";
            r.packerIndicators.addAll(r.dynamicLoadingEvidence);
        } else if (r.dynamicDexLoading) {
            // a known packer + dynamic loading — note it as extra evidence
            r.packerIndicators.addAll(r.dynamicLoadingEvidence);
        }
    }

    /** Framework packages excluded from obfuscation metrics (they're never obfuscated). */
    private static final String[] FRAMEWORK = {
        "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.",
        "com.google.", "com.android.", "com.facebook.", "com.squareup.",
        "okhttp3.", "okio.", "retrofit2.", "io.reactivex.", "org.json.",
        "org.apache.", "org.bouncycastle.", "dagger.", "rx.", "io.flutter.",
        "com.bumptech.", "com.airbnb.", "expo.", "bolts."
    };

    private static boolean isFramework(String cls) {
        for (String f : FRAMEWORK) if (cls.startsWith(f)) return true;
        return false;
    }

    private static void detectObfuscation(Report r, List<String> classNames) {
        if (classNames.isEmpty()) return;

        // Measure obfuscation only over the app's OWN classes — RN/library apps have
        // tens of thousands of normally-named framework classes that would dilute the
        // ratio to ~0% and hide heavy obfuscation of the real app code.
        int appClasses = 0, shortNamed = 0, singleLetterPkg = 0, nonAscii = 0;
        for (String n : classNames) {
            if (isFramework(n)) continue;
            appClasses++;
            String simple = n.substring(n.lastIndexOf('.') + 1);
            int dollar = simple.indexOf('$');
            if (dollar > 0) simple = simple.substring(0, dollar);
            if (simple.matches("[a-zA-Z]{1,2}")) shortNamed++;
            // single-letter package segment, e.g. "o.en.b", "a.b.c"
            for (String seg : n.split("\\.")) {
                if (seg.length() == 1 && Character.isLetter(seg.charAt(0))) { singleLetterPkg++; break; }
            }
            if (n.matches(".*[\\u0080-\\uFFFF].*")) nonAscii++;
        }
        if (appClasses == 0) appClasses = classNames.size(); // no manifest/app split — use all

        double ratio = (double) shortNamed / appClasses;
        double pkgRatio = (double) singleLetterPkg / appClasses;
        int pct = 0;
        if (ratio > 0.5 || pkgRatio > 0.4) pct = 90;
        else if (ratio > 0.3 || pkgRatio > 0.2) pct = 75;
        else if (ratio > 0.15 || pkgRatio > 0.08) pct = 55;
        else if (ratio > 0.05) pct = 30;
        r.obfuscationPercent = pct;

        if (shortNamed > 0)
            r.obfuscationIndicators.add(String.format(
                "%.0f%% of app classes have 1-2 char names (%d/%d)", ratio * 100, shortNamed, appClasses));
        if (singleLetterPkg > 10)
            r.obfuscationIndicators.add(singleLetterPkg + " classes in single-letter packages (e.g. o.*, a.*)");

        // obfuscator guess
        if (nonAscii > 5) {
            r.obfuscator = "DexGuard / Allatori (non-ASCII identifier obfuscation)";
            r.obfuscationIndicators.add(nonAscii + " classes use non-ASCII identifiers (advanced commercial obfuscator)");
        } else if (pkgRatio > 0.15 && pct >= 75) {
            r.obfuscator = "DexGuard / advanced (name + likely control-flow obfuscation)";
            r.obfuscationIndicators.add("Heavy short-package layout — typical of DexGuard control-flow flattening");
        } else if (pct >= 30) {
            r.obfuscator = "ProGuard / R8";
        } else {
            r.obfuscator = "None / minimal";
        }
    }

    private static void detectAntiRE(Report r, Project project, DexAnalyzer dex, Set<String> soNames) {
        // collect a haystack: dex strings + class names + native strings + .so names
        StringBuilder hay = new StringBuilder();
        for (String s : dex.getStrings()) hay.append(s).append('\n');
        for (DexAnalyzer.ClassInfo c : dex.getClasses()) hay.append(c.name).append('\n');
        for (String so : soNames) hay.append(so).append('\n');
        // native strings + JNI
        for (String so : project.getNativeAnalyzer().listSoEntries()) {
            try {
                ElfFile elf = project.getNativeAnalyzer().parse(so);
                for (String s : elf.getStrings()) hay.append(s).append('\n');
                for (ElfFile.Symbol sym : elf.getJniFunctions()) hay.append(sym.name).append('\n');
            } catch (Exception ignored) {}
        }
        String low = hay.toString().toLowerCase();

        match(low, FRIDA, r.antiFrida);
        match(low, ROOT, r.antiRoot);
        match(low, DEBUG, r.antiDebug);
        match(low, EMU, r.antiEmulator);
    }

    private static void match(String haystack, String[] needles, List<String> out) {
        for (String n : needles) {
            if (haystack.contains(n.toLowerCase()) && !out.contains(n)) out.add(n);
        }
    }

    private static void scanEntropy(Report r, Project project) {
        try (ZipFile zip = new ZipFile(project.getApkFile())) {
            var en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || e.getSize() < 1024) continue;
                // standard, expected files we skip unless suspicious
                boolean inAssets = name.startsWith("assets/");
                boolean isResArsc = name.equals("resources.arsc");
                boolean isDex = name.endsWith(".dex");
                if (!inAssets && !isDex && !name.endsWith(".so") && !name.startsWith("root/")) continue;

                double ent = entropy(zip, e);
                String note = "";
                if (isDex && ent > 7.6) note = "DEX with very high entropy — likely encrypted/packed";
                else if (inAssets && ent > 7.5) note = "high-entropy asset — likely encrypted DEX/payload";
                else if (name.endsWith(".so") && ent > 7.9) note = "native lib unusually high entropy (VM-protected?)";
                if (!note.isEmpty()) {
                    r.encryptedPayloads.add(new EncryptedFile(name, ent, e.getSize(), note));
                }
            }
        } catch (Exception ignored) {}
    }

    private static double entropy(ZipFile zip, ZipEntry e) throws Exception {
        long[] freq = new long[256];
        long total = 0;
        try (InputStream in = zip.getInputStream(e)) {
            byte[] buf = new byte[8192];
            int n;
            long cap = 2_000_000; // sample up to 2MB
            while ((n = in.read(buf)) > 0 && total < cap) {
                for (int i = 0; i < n; i++) freq[buf[i] & 0xff]++;
                total += n;
            }
        }
        if (total == 0) return 0;
        double ent = 0;
        for (long f : freq) {
            if (f == 0) continue;
            double p = (double) f / total;
            ent -= p * (Math.log(p) / Math.log(2));
        }
        return ent;
    }
}
