package com.mythosaur.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Resilient APK ingestion.
 *
 * <p>Hardened / malicious APKs deliberately corrupt the ZIP <b>central directory</b> so that
 * Java's strict {@link java.util.zip.ZipFile} refuses to open the archive, while Android's
 * lenient loader installs it fine. Observed tricks in the wild:
 * <ul>
 *   <li>fake compression method in the CEN header (e.g. {@code 31573}, {@code 9283}) — the
 *       data is really STORE/DEFLATE, only the directory field lies;</li>
 *   <li>the "encrypted entry" general-purpose flag bit set on a non-encrypted entry;</li>
 *   <li>hundreds of decoy entries with path-traversal / absurdly-deep / non-ASCII names that
 *       break on-disk extraction;</li>
 *   <li>duplicate entry names.</li>
 * </ul>
 *
 * <p>This class reads the archive the way Android does — tolerantly — by walking the central
 * directory, recovering each entry's real bytes from its <b>local file header</b> (ignoring the
 * lying CEN fields), and rewriting a clean, valid {@code normalized.apk}. Every downstream
 * consumer (jadx, dexlib2, the native/entropy scanners, the patch pipeline) is then pointed at
 * that normalized archive, so none of them need to know the original was malformed.
 *
 * <p>For a well-formed APK this is a no-op fast path: the original file is returned untouched,
 * so normal apps behave exactly as before.
 */
public final class ApkSanitizer {

    /** Outcome of a sanitize pass. */
    public static final class Result {
        /** The archive downstream tools should read — original (clean) or normalized (repaired). */
        public final File workingApk;
        /** True if the original archive was malformed and had to be repaired. */
        public final boolean repaired;
        /** Human-readable notes on what tampering was found / fixed. */
        public final List<String> notes;
        public final int entriesRecovered;
        public final int entriesDropped;

        Result(File workingApk, boolean repaired, List<String> notes, int recovered, int dropped) {
            this.workingApk = workingApk;
            this.repaired = repaired;
            this.notes = notes;
            this.entriesRecovered = recovered;
            this.entriesDropped = dropped;
        }
    }

    private ApkSanitizer() {}

    // ZIP signatures (little-endian on disk).
    private static final int SIG_EOCD   = 0x06054b50;
    private static final int SIG_CEN    = 0x02014b50;
    private static final int SIG_LFH    = 0x04034b50;
    private static final int SIG_ZIP64_EOCD     = 0x06064b50;
    private static final int SIG_ZIP64_LOCATOR  = 0x07064b50;

    /**
     * Ensure we have an archive Java can read. Returns the original on the fast path, or a
     * repaired {@code normalized.apk} written into {@code workspace} when the original is malformed.
     */
    public static Result sanitize(File apk, Path workspace) {
        // Fast path: a well-formed archive is used as-is (no behavioural change for normal apps).
        if (opensCleanly(apk)) {
            return new Result(apk, false, List.of(), -1, 0);
        }

        List<String> notes = new ArrayList<>();
        try {
            byte[] data = Files.readAllBytes(apk.toPath());
            List<CenEntry> entries = parseCentralDirectory(data, notes);
            if (entries.isEmpty()) {
                notes.add("Could not locate any central-directory entries — passing original through.");
                return new Result(apk, false, notes, 0, 0);
            }

            // Recover bytes + sanitize names, de-duplicating.
            Map<String, byte[]> out = new LinkedHashMap<>();
            Set<String> usedNames = new HashSet<>();
            int recovered = 0, dropped = 0, badMethod = 0, fakeEncrypted = 0, remapped = 0;

            for (CenEntry e : entries) {
                if (e.name.endsWith("/") && e.uncompressedSize == 0) continue; // directory marker
                if (e.methodLied) badMethod++;
                if (e.encryptedFlag) fakeEncrypted++;

                byte[] bytes = recoverData(data, e);
                if (bytes == null) { dropped++; continue; }

                String safe = safeName(e.name);
                if (!safe.equals(e.name)) remapped++;
                // de-dupe
                String name = safe;
                int n = 1;
                while (usedNames.contains(name)) {
                    int dot = safe.lastIndexOf('.');
                    name = (dot > 0 ? safe.substring(0, dot) + "_" + n + safe.substring(dot)
                                    : safe + "_" + n);
                    n++;
                }
                usedNames.add(name);
                out.put(name, bytes);
                recovered++;
            }

            if (badMethod > 0)     notes.add(badMethod + " entr" + (badMethod == 1 ? "y has" : "ies have")
                    + " a fake compression method in the ZIP directory (anti-analysis).");
            if (fakeEncrypted > 0) notes.add(fakeEncrypted + " entr" + (fakeEncrypted == 1 ? "y is" : "ies are")
                    + " flagged 'encrypted' in the ZIP directory but are not (anti-analysis).");
            if (remapped > 0)      notes.add(remapped + " decoy entr" + (remapped == 1 ? "y" : "ies")
                    + " with unsafe path-traversal/deep/non-ASCII names were renamed.");

            File normalized = workspace.resolve("normalized.apk").toFile();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(normalized))) {
                for (Map.Entry<String, byte[]> en : out.entrySet()) {
                    try {
                        ZipEntry ze = new ZipEntry(en.getKey());
                        zos.putNextEntry(ze);
                        zos.write(en.getValue());
                        zos.closeEntry();
                    } catch (Exception ignore) { /* skip a single bad entry, keep the rest */ }
                }
            }

            // Sanity: the repaired archive must open cleanly now.
            if (!opensCleanly(normalized)) {
                notes.add("Repaired archive still failed to open — passing original through.");
                return new Result(apk, false, notes, recovered, dropped);
            }
            notes.add(0, "Repaired malformed APK → normalized.apk (" + recovered + " entries recovered, "
                    + dropped + " unrecoverable).");
            return new Result(normalized, true, notes, recovered, dropped);

        } catch (Exception ex) {
            notes.add("Sanitizer error: " + ex + " — passing original through.");
            return new Result(apk, false, notes, 0, 0);
        }
    }

    private static boolean opensCleanly(File f) {
        try (ZipFile z = new ZipFile(f)) {
            z.size();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- central-directory parsing -------------------------------------------------------

    private static final class CenEntry {
        String name;
        int method;            // as declared in CEN (may be a lie)
        int gpFlag;
        long crc;
        long compressedSize;
        long uncompressedSize;
        long localHeaderOffset;
        boolean methodLied;
        boolean encryptedFlag;
    }

    private static List<CenEntry> parseCentralDirectory(byte[] d, List<String> notes) {
        List<CenEntry> entries = new ArrayList<>();

        int eocd = lastIndexOf(d, SIG_EOCD);
        if (eocd < 0 || eocd + 22 > d.length) return entries;

        long cenOffset = u32(d, eocd + 16);
        long cenSize   = u32(d, eocd + 12);
        int  total     = u16(d, eocd + 10);

        // ZIP64: if any field is saturated, prefer the ZIP64 EOCD record.
        if (cenOffset == 0xFFFFFFFFL || cenSize == 0xFFFFFFFFL || total == 0xFFFF) {
            int loc = lastIndexOf(d, SIG_ZIP64_LOCATOR);
            if (loc >= 0 && loc + 16 <= d.length) {
                long z64 = u64(d, loc + 8);
                if (z64 >= 0 && z64 + 56 <= d.length && (int) u32(d, (int) z64) == SIG_ZIP64_EOCD) {
                    total     = (int) u64(d, (int) z64 + 32);
                    cenOffset = u64(d, (int) z64 + 48);
                }
            }
        }

        if (cenOffset < 0 || cenOffset >= d.length) {
            // central dir offset is also a lie — scan for the first CEN signature.
            int scan = indexOf(d, SIG_CEN, 0);
            if (scan < 0) return entries;
            cenOffset = scan;
        }

        int off = (int) cenOffset;
        int guard = 0;
        while (off >= 0 && off + 46 <= d.length && (int) u32(d, off) == SIG_CEN) {
            CenEntry e = new CenEntry();
            e.gpFlag            = u16(d, off + 8);
            e.method            = u16(d, off + 10);
            e.crc               = u32(d, off + 16);
            e.compressedSize    = u32(d, off + 20);
            e.uncompressedSize  = u32(d, off + 24);
            int nameLen         = u16(d, off + 28);
            int extraLen        = u16(d, off + 30);
            int commentLen      = u16(d, off + 32);
            e.localHeaderOffset = u32(d, off + 42);

            int nameStart = off + 46;
            if (nameStart + nameLen > d.length) break;
            e.name = new String(d, nameStart, nameLen, java.nio.charset.StandardCharsets.UTF_8);

            // ZIP64 extra field (id 0x0001) can carry the real sizes / LFH offset.
            if (e.uncompressedSize == 0xFFFFFFFFL || e.compressedSize == 0xFFFFFFFFL
                    || e.localHeaderOffset == 0xFFFFFFFFL) {
                parseZip64Extra(d, nameStart + nameLen, extraLen, e);
            }

            e.encryptedFlag = (e.gpFlag & 0x01) != 0;
            e.methodLied = (e.method != 0 && e.method != 8);
            entries.add(e);

            off += 46 + nameLen + extraLen + commentLen;
            if (++guard > 1_000_000) break;
        }
        return entries;
    }

    private static void parseZip64Extra(byte[] d, int start, int extraLen, CenEntry e) {
        int p = start, end = Math.min(start + extraLen, d.length);
        while (p + 4 <= end) {
            int id = u16(d, p);
            int sz = u16(d, p + 2);
            int body = p + 4;
            if (id == 0x0001) {
                int q = body;
                if (e.uncompressedSize == 0xFFFFFFFFL && q + 8 <= end) { e.uncompressedSize = u64(d, q); q += 8; }
                if (e.compressedSize   == 0xFFFFFFFFL && q + 8 <= end) { e.compressedSize   = u64(d, q); q += 8; }
                if (e.localHeaderOffset == 0xFFFFFFFFL && q + 8 <= end) { e.localHeaderOffset = u64(d, q); q += 8; }
                return;
            }
            p = body + sz;
        }
    }

    // ---- per-entry byte recovery ---------------------------------------------------------

    /**
     * Recover an entry's real bytes, reading from the local file header and <b>ignoring the CEN/LFH
     * lies</b>. Malware tampers the compression method (so neither header is trustworthy) and the
     * compressed size (so a STORED manifest looks 23 KB shorter than it is). We therefore:
     * <ul>
     *   <li>derive the method only from a valid CEN/LFH field, else infer it;</li>
     *   <li>for DEFLATE, feed the inflater everything to EOF — it stops at the real stream end;</li>
     *   <li>for STORED, trust the uncompressed size (what Android uses), not the compressed size.</li>
     * </ul>
     */
    private static byte[] recoverData(byte[] d, CenEntry e) {
        try {
            int lfh = (int) e.localHeaderOffset;
            if (lfh < 0 || lfh + 30 > d.length || (int) u32(d, lfh) != SIG_LFH) {
                return null; // LFH offset is bogus — give up on this entry.
            }
            int lfhMethod   = u16(d, lfh + 8);
            int lfhNameLen  = u16(d, lfh + 26);
            int lfhExtraLen = u16(d, lfh + 28);
            int dataStart = lfh + 30 + lfhNameLen + lfhExtraLen;
            if (dataStart < 0 || dataStart >= d.length) return null;
            int avail = d.length - dataStart;

            // Only a method field that is actually valid can be trusted; otherwise infer it.
            int method = (e.method == 0 || e.method == 8) ? e.method
                       : (lfhMethod == 0 || lfhMethod == 8) ? lfhMethod
                       : -1;

            boolean tryInflate = (method == 8)
                    || (method == -1 && e.compressedSize > 0 && e.compressedSize < e.uncompressedSize);
            if (tryInflate) {
                byte[] inflated = rawInflate(d, dataStart, avail); // stops at the real stream end
                if (inflated != null) return inflated;
                // inflate failed → the bytes were really stored; fall through.
            }

            // STORED — the uncompressed size is authoritative (it's what Android reads); the
            // compressed-size field is the one malware shrinks. Fall back to the gap-to-next-record
            // only when the uncompressed size is itself absurd.
            int len;
            if (e.uncompressedSize > 0 && e.uncompressedSize <= avail) {
                len = (int) e.uncompressedSize;
            } else {
                int next = nextSignature(d, dataStart);
                len = (next < 0 ? d.length : next) - dataStart;
                if (len <= 0 || len > avail) len = avail;
            }
            byte[] out = new byte[len];
            System.arraycopy(d, dataStart, out, 0, len);
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    private static byte[] rawInflate(byte[] d, int start, int len) {
        Inflater inf = new Inflater(true); // nowrap: raw DEFLATE, as stored in ZIP
        try {
            inf.setInput(d, start, len);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, len * 3));
            byte[] buf = new byte[64 * 1024];
            int produced = 0;
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.finished() || inf.needsDictionary()) break;
                    if (inf.needsInput()) break; // truncated input — keep what we have
                }
                bos.write(buf, 0, n);
                produced += n;
                if (produced > 512 * 1024 * 1024) break; // 512MB guard
            }
            return produced > 0 ? bos.toByteArray() : null;
        } catch (Exception e) {
            return null;
        } finally {
            inf.end();
        }
    }

    // ---- name sanitization ---------------------------------------------------------------

    /**
     * Make an entry name safe for a clean archive + on-disk extraction. Real structural files
     * (classes*.dex, AndroidManifest.xml, resources.arsc, lib/**, normal assets/res) pass through
     * unchanged; pathological decoy names are remapped to a flat, safe path under {@code _decoys/}
     * (kept as assets so the entropy/payload scan can still see them).
     */
    private static String safeName(String name) {
        String n = name.replace('\\', '/');
        // strip leading slashes / drive-ish prefixes
        while (n.startsWith("/")) n = n.substring(1);

        boolean unsafe = false;
        String[] segs = n.split("/");
        if (segs.length > 12) unsafe = true; // absurd nesting (decoy)
        for (String s : segs) {
            if (s.equals("..") || s.isEmpty() && segs.length > 1) { unsafe = true; }
            if (s.length() > 96) unsafe = true;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                    unsafe = true; break;
                }
                if (c > 0x7F) unsafe = true; // non-ASCII filename — decoy / extraction hazard
            }
        }
        if (!unsafe && !n.isEmpty()) return n;

        // Remap: keep the data (and an assets/ prefix so payload scanners still inspect it),
        // derive a stable-ish name from a hash of the original.
        String base = "_decoys/" + Integer.toHexString(name.hashCode() & 0x7fffffff);
        if (name.endsWith(".dex")) return base + ".dex";
        if (name.endsWith(".so"))  return base + ".so";
        return "assets/" + base + ".bin";
    }

    // ---- little-endian readers + signature search ----------------------------------------

    private static int u16(byte[] d, int o) { return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8); }

    private static long u32(byte[] d, int o) {
        return (d[o] & 0xffL) | ((d[o + 1] & 0xffL) << 8) | ((d[o + 2] & 0xffL) << 16) | ((d[o + 3] & 0xffL) << 24);
    }

    private static long u64(byte[] d, int o) {
        long lo = u32(d, o), hi = u32(d, o + 4);
        return lo | (hi << 32);
    }

    private static int lastIndexOf(byte[] d, int sig) {
        byte b0 = (byte) sig, b1 = (byte) (sig >> 8), b2 = (byte) (sig >> 16), b3 = (byte) (sig >> 24);
        for (int i = d.length - 4; i >= 0; i--) {
            if (d[i] == b0 && d[i + 1] == b1 && d[i + 2] == b2 && d[i + 3] == b3) return i;
        }
        return -1;
    }

    private static int indexOf(byte[] d, int sig, int from) {
        byte b0 = (byte) sig, b1 = (byte) (sig >> 8), b2 = (byte) (sig >> 16), b3 = (byte) (sig >> 24);
        for (int i = Math.max(0, from); i + 4 <= d.length; i++) {
            if (d[i] == b0 && d[i + 1] == b1 && d[i + 2] == b2 && d[i + 3] == b3) return i;
        }
        return -1;
    }

    /** Next local-file-header or central-directory signature at or after {@code from}. */
    private static int nextSignature(byte[] d, int from) {
        int a = indexOf(d, SIG_LFH, from);
        int b = indexOf(d, SIG_CEN, from);
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }
}
