package com.mythosaur.core;

import com.strobel.assembler.metadata.JarTypeLoader;
import com.strobel.decompiler.Decompiler;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Multi-decompiler hub — the "second opinion" engine. The same class can be decompiled
 * by jadx (DEX-direct) and by CFR / Vineflower / Procyon (via the {@link DexToJar}
 * bridge). Each result is scored for quality so the UI can offer a <b>Best</b> mode that
 * automatically surfaces whichever decompiler produced the cleanest output — useful when
 * jadx chokes on obfuscated control flow but another engine reconstructs it.
 *
 * <p>All engines are open-source and embedded as libraries; nothing shells out.
 */
public final class Decompilers {

    public enum Engine {
        JADX("jadx"), CFR("CFR"), VINEFLOWER("Vineflower"), PROCYON("Procyon");
        public final String label;
        Engine(String l) { this.label = l; }
    }

    /** One engine's decompilation of a class, with a 0–100 quality score. */
    public record Result(Engine engine, String source, int score, String note) {}

    private final Project project;
    private Path cachedJar;     // alt-decompile.jar once built

    public Decompilers(Project project) { this.project = project; }

    /** Decompile with one engine. May trigger the (cached) dex2jar build for non-jadx engines. */
    public Result decompile(Engine engine, String dottedClass) {
        try {
            String src = switch (engine) {
                case JADX -> jadx(dottedClass);
                case CFR -> cfr(dottedClass);
                case VINEFLOWER -> vineflower(dottedClass);
                case PROCYON -> procyon(dottedClass);
            };
            if (src == null || src.isBlank()) return new Result(engine, "", 0, "no output");
            return new Result(engine, src, score(src), "");
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            return new Result(engine, "// " + engine.label + " failed: " + msg, 0, msg);
        }
    }

    /** Decompile with every available engine, best score first. */
    public List<Result> decompileAll(String dottedClass) {
        List<Result> out = new ArrayList<>();
        for (Engine e : Engine.values()) out.add(decompile(e, dottedClass));
        out.sort(Comparator.comparingInt(Result::score).reversed());
        return out;
    }

    // ---------- engines ----------

    private String jadx(String dotted) {
        var cls = project.findClass(dotted);
        return cls != null ? cls.getCode() : null;
    }

    private synchronized Path jar() throws Exception {
        if (cachedJar == null) cachedJar = DexToJar.ensureJar(project);
        return cachedJar;
    }

    private String procyon(String dotted) throws Exception {
        DecompilerSettings settings = DecompilerSettings.javaDefaults();
        settings.setTypeLoader(new JarTypeLoader(new JarFile(jar().toFile())));
        StringWriter sw = new StringWriter();
        Decompiler.decompile(dotted.replace('.', '/'), new PlainTextOutput(sw), settings);
        return sw.toString();
    }

    private String cfr(String dotted) throws Exception {
        Path classFile = extractClass(dotted);
        StringBuilder out = new StringBuilder();
        OutputSinkFactory sink = new OutputSinkFactory() {
            public List<SinkClass> getSupportedSinks(SinkType t, Collection<SinkClass> a) {
                return t == SinkType.JAVA ? Arrays.asList(SinkClass.DECOMPILED, SinkClass.STRING)
                        : Collections.singletonList(SinkClass.STRING);
            }
            @SuppressWarnings("unchecked")
            public <T> Sink<T> getSink(SinkType t, SinkClass c) {
                if (t == SinkType.JAVA && c == SinkClass.DECOMPILED)
                    return (Sink<T>) (Sink<SinkReturns.Decompiled>) d -> out.append(d.getJava());
                return x -> {};
            }
        };
        Map<String, String> opts = new HashMap<>();
        opts.put("extraclasspath", jar().toString());
        opts.put("comments", "false");
        new CfrDriver.Builder().withOutputSink(sink).withOptions(opts).build()
                .analyse(Collections.singletonList(classFile.toString()));
        return out.toString();
    }

    private String vineflower(String dotted) throws Exception {
        Path classFile = extractClass(dotted);
        StringBuilder captured = new StringBuilder();
        IResultSaver saver = new SaverAdapter() {
            public void saveClassFile(String path, String qn, String en, String content, int[] map) {
                captured.append(content);
            }
        };
        Map<String, Object> opts = new HashMap<>();
        opts.put("hdc", "0");   // hide default constructors off (keep output complete)
        BaseDecompiler dec = new BaseDecompiler(saver, opts, new QuietLogger());
        dec.addSource(classFile.toFile());
        dec.addLibrary(jar().toFile());
        dec.decompileContext();
        return captured.toString();
    }

    /** Extract a class (+ its inner classes) from the cached jar into a temp dir. */
    private Path extractClass(String dotted) throws Exception {
        String internal = dotted.replace('.', '/');
        Path dir = Files.createTempDirectory("myth-dec");
        dir.toFile().deleteOnExit();
        Path classFile = null;
        try (ZipFile zf = new ZipFile(jar().toFile())) {
            var it = zf.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                String n = e.getName();
                if (n.equals(internal + ".class") || n.startsWith(internal + "$")) {
                    Path target = dir.resolve(n);
                    Files.createDirectories(target.getParent());
                    try (var in = zf.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    target.toFile().deleteOnExit();
                    if (n.equals(internal + ".class")) classFile = target;
                }
            }
        }
        if (classFile == null) throw new IllegalStateException("class not in dex2jar output: " + dotted);
        return classFile;
    }

    // ---------- quality scoring ----------

    private static final Pattern STRUCT =
            Pattern.compile("\\b(if|for|while|switch|try|catch|return|new)\\b");
    private static final String[] HARD_FAIL = {
            "could not be decompiled", "Couldn't be decompiled", "Method not decompiled",
            "Unable to fully structure", "UnsupportedOperationException(\"Method"
    };
    private static final String[] JADX_MARK = {
            "/* JADX ERROR", "/* JADX WARN", "// JADX", "Code restructure failed",
            "Failed to decode", "decompiled incorrectly", "Method dump skipped"
    };

    /**
     * Heuristic 0–100 quality score. The aim isn't perfection — it's to let "Best" prefer
     * a clean reconstruction over one that gave up (raw gotos, error markers, stubs).
     */
    static int score(String src) {
        if (src == null || src.length() < 40) return 0;
        int s = 60;

        for (String f : HARD_FAIL) s -= 30 * count(src, f);
        for (String m : JADX_MARK) s -= 10 * count(src, m);

        // bare `goto`/labels mean the decompiler fell back to unstructured control flow
        // (comments themselves aren't penalized — decompilers legitimately annotate output)
        s -= 4 * count(src, "goto ");
        s -= 12 * count(src, "throw new UnsupportedOperationException");

        // reward reconstructed control flow (capped so size alone can't win)
        var m = STRUCT.matcher(src);
        int kw = 0; while (m.find() && kw < 40) kw++;
        s += Math.min(kw, 40);

        // a reasonable body (not an empty stub) is a good sign
        if (src.length() > 400) s += 8;

        return Math.max(0, Math.min(100, s));
    }

    /** Public quality score for a decompiled source (used by the UI's auto-fallback). */
    public static int quality(String src) { return score(src); }

    private static int count(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ---------- Vineflower adapters ----------

    private static final class QuietLogger extends IFernflowerLogger {
        public void writeMessage(String m, Severity s) {}
        public void writeMessage(String m, Severity s, Throwable t) {}
    }

    private abstract static class SaverAdapter implements IResultSaver {
        public void saveFolder(String path) {}
        public void copyFile(String source, String path, String entryName) {}
        public void createArchive(String path, String archiveName, Manifest manifest) {}
        public void saveDirEntry(String path, String archiveName, String entryName) {}
        public void copyEntry(String source, String path, String archiveName, String entry) {}
        public void saveClassEntry(String path, String archiveName, String qn, String entryName, String content) {}
        public void closeArchive(String path, String archiveName) {}
    }
}
