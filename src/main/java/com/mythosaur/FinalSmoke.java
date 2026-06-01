package com.mythosaur;

import com.mythosaur.core.*;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;

import java.io.File;

/** Final end-to-end readiness smoke — exercises every major subsystem on an APK. */
public class FinalSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        System.setProperty("org.slf4j.simpleLogger.log.jadx", "off");
        for (String path : args) run(new File(path));
    }

    static void run(File apk) {
        System.out.println("\n################ " + apk.getName() + " ################");
        try {
            long t0 = System.currentTimeMillis();
            Project p = ApkLoader.load(apk, null, m -> {});
            ok("load", (System.currentTimeMillis() - t0) + "ms");

            var an = p.getAnalyzer();
            ok("analysis", an.getTotalClasses() + " classes · " + an.getTotalMethods() + " methods · " + an.getTotalStrings() + " strings");

            var mf = p.getManifest();
            ok("manifest", "pkg=" + mf.getPackageName() + " ver=" + mf.getVersionName()
                    + " min=" + mf.getMinSdk() + " [" + mf.getSource() + "]  perms=" + mf.getPermissions().size());

            ok("signature", firstLine(SignatureInfo.report(apk)));
            ok("flow", p.getManifest().getActivities().size() + " activities · " + p.getFlowAnalyzer().getEdges().size() + " edges");

            var rep = ProtectionAnalyzer.analyze(p);
            ok("protections", "packed=" + rep.packed + " obf=" + rep.obfuscationPercent + "% dynDex=" + rep.dynamicDexLoading);

            var pr = p.getPermissionAnalyzer().analyze();
            long abused = pr.permissions.stream().filter(PermissionAnalyzer.Perm::abused).count();
            ok("permissions", pr.dangerousCount + " dangerous · " + abused + " abused · " + pr.overPrivilegedCount + " over-privileged");

            ok("native", p.getNativeAnalyzer().listSoEntries().size() + " .so");
            ok("resources", p.getJadx().getResources().size() + " entries");

            // dry-run a static String(int) method
            Method dec = findDecryptor(p);
            if (dec != null) {
                var r = new DalvikInterpreter(p, 100_000).trace(false).run(dec, new Object[]{0});
                ok("dry-run", r.returned ? "ran → " + trim(r.display()) : "n/a");
            } else ok("dry-run", "no candidate (engine present)");

            // multi-decompiler best on the first app class
            String cls = an.getClasses().isEmpty() ? null : an.getClasses().get(0).name;
            if (cls != null) {
                var best = new Decompilers(p).decompileAll(cls).get(0);
                ok("decompilers", "best=" + best.engine().label + " score=" + best.score());
            }

            p.close();
            System.out.println(">>> " + apk.getName() + " : ALL SUBSYSTEMS OK");
        } catch (Throwable t) {
            System.out.println("!!! FAILED: " + t);
            t.printStackTrace();
        }
    }

    static Method findDecryptor(Project p) {
        for (DexBackedDexFile dex : p.getDexFiles())
            for (ClassDef c : dex.getClasses())
                for (Method m : c.getMethods())
                    if (m.getImplementation() != null && AccessFlags.STATIC.isSet(m.getAccessFlags())
                            && "Ljava/lang/String;".equals(m.getReturnType())
                            && m.getParameterTypes().size() == 1 && "I".equals(m.getParameterTypes().get(0).toString()))
                        return m;
        return null;
    }

    static void ok(String name, String detail) { System.out.printf("  ✓ %-12s %s%n", name, detail); }
    static String firstLine(String s) { int i = s.indexOf('\n'); return i < 0 ? s : s.substring(0, i); }
    static String trim(String s) { return s.length() > 50 ? s.substring(0, 48) + "…" : s; }
}
