package com.mythosaur.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tolerant Android binary-XML (AXML) decoder.
 *
 * <p>jadx / aapt / apktool decode the manifest with strict parsers that crash on malware that
 * tampers the AXML string pool (bad offsets/lengths), fakes the outer chunk type, or pads junk
 * "dummy" chunks between elements. This decoder mirrors what androguard does to survive that:
 * it bounds-checks every read, skips unknown/garbage chunks instead of aborting, recovers each
 * string independently, and resolves attribute names that have no string (only a resource id)
 * via a built-in table of the well-known {@code android:} attribute resource ids.
 *
 * <p>Output is plain text XML — good enough for {@link ManifestParser}'s regex extraction
 * (package, permissions, components, exported flags, launcher intent-filters). It is a
 * best-effort reconstruction, not a byte-perfect re-encode.
 */
public final class AxmlDecoder {

    // chunk types
    private static final int STRING_POOL   = 0x0001;
    private static final int START_NS      = 0x0100;
    private static final int END_NS        = 0x0101;
    private static final int START_ELEMENT = 0x0102;
    private static final int END_ELEMENT   = 0x0103;
    private static final int CDATA         = 0x0104;
    private static final int RESOURCE_MAP  = 0x0180;

    // typed-value data types
    private static final int TYPE_REFERENCE  = 0x01;
    private static final int TYPE_STRING      = 0x03;
    private static final int TYPE_INT_DEC     = 0x10;
    private static final int TYPE_INT_HEX     = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** Well-known android:* attribute resource ids → names (stable across Android versions). */
    private static final Map<Long, String> ATTR_IDS = new HashMap<>();
    static {
        ATTR_IDS.put(0x01010000L, "theme");
        ATTR_IDS.put(0x01010001L, "label");
        ATTR_IDS.put(0x01010002L, "icon");
        ATTR_IDS.put(0x01010003L, "name");
        ATTR_IDS.put(0x01010006L, "permission");
        ATTR_IDS.put(0x0101000eL, "enabled");
        ATTR_IDS.put(0x0101000fL, "debuggable");
        ATTR_IDS.put(0x01010010L, "exported");
        ATTR_IDS.put(0x01010011L, "process");
        ATTR_IDS.put(0x01010018L, "authorities");
        ATTR_IDS.put(0x01010024L, "value");
        ATTR_IDS.put(0x01010025L, "resource");
        ATTR_IDS.put(0x0101020cL, "minSdkVersion");
        ATTR_IDS.put(0x0101021bL, "versionCode");
        ATTR_IDS.put(0x0101021cL, "versionName");
        ATTR_IDS.put(0x01010270L, "targetSdkVersion");
        ATTR_IDS.put(0x01010572L, "compileSdkVersion");
        ATTR_IDS.put(0x01010573L, "compileSdkVersionCodename");
    }

    private final byte[] d;
    private String[] strings = new String[0];
    private long[] resourceMap = new long[0];

    private AxmlDecoder(byte[] data) { this.d = data; }

    /** Decode binary AXML to text XML, or return {@code null} if it cannot be recovered at all. */
    public static String decode(byte[] axml) {
        try {
            return new AxmlDecoder(axml).run();
        } catch (Throwable t) {
            return null;
        }
    }

    private String run() {
        // Outer header is normally type=0x0003 (RES_XML) but malware fakes it; don't require it.
        // Walk chunks from the first one after the 8-byte file header.
        int p = 8;
        // First locate + parse the string pool (it may not be the very first chunk).
        int spOff = findChunk(STRING_POOL, 8);
        if (spOff >= 0) parseStringPool(spOff);

        StringBuilder xml = new StringBuilder(1 << 16);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");

        Deque<String> stack = new ArrayDeque<>();
        Map<String, String> nsPrefix = new HashMap<>(); // uri -> prefix
        boolean emittedRoot = false;

        int len = d.length;
        while (p + 8 <= len) {
            int type = u16(p);
            int headerSize = u16(p + 2);
            long size = u32(p + 4);
            if (size < 8 || p + size > len) {
                // garbage / dummy data — step to the next plausible chunk start.
                int np = nextChunk(p + 4);
                if (np <= p) break;
                p = np;
                continue;
            }
            int next = (int) (p + size);

            switch (type) {
                case START_NS -> {
                    // body: lineNo(4) comment(4) prefix(4) uri(4)
                    int prefixRef = i32(p + headerSize);
                    int uriRef = i32(p + headerSize + 4);
                    String uri = str(uriRef), pre = str(prefixRef);
                    if (uri != null && pre != null) nsPrefix.put(uri, pre);
                }
                case END_NS -> { /* nothing */ }
                case START_ELEMENT -> {
                    int base = p + headerSize; // after lineNo+comment, headerSize is 16
                    int nsRef   = i32(base);
                    int nameRef = i32(base + 4);
                    int attrStart = u16(base + 8);
                    int attrSize  = u16(base + 10);
                    int attrCount = u16(base + 12);

                    String elem = str(nameRef);
                    if (elem == null || elem.isEmpty()) elem = "node";
                    StringBuilder tag = new StringBuilder();
                    tag.append('<').append(elem);

                    int ab = base + (attrStart > 0 ? attrStart : 20);
                    int stride = attrSize > 0 ? attrSize : 20;
                    for (int i = 0; i < attrCount; i++) {
                        int a = ab + i * stride;
                        if (a + 20 > next || a + 20 > len) break;
                        int aNs   = i32(a);
                        int aName = i32(a + 4);
                        int aRaw  = i32(a + 8);
                        int dataType = d[a + 15] & 0xff;
                        long dataVal = u32(a + 16);

                        String an = resolveAttrName(aName, i, attrCount);
                        if (an == null || an.isEmpty()) continue;
                        String prefix = (aNs >= 0) ? nsPrefix.getOrDefault(str(aNs), null) : null;
                        if (prefix == null && isAndroidAttr(aName)) prefix = "android";
                        String qName = (prefix != null ? prefix + ":" : "") + an;

                        String val = attrValue(dataType, dataVal, aRaw);
                        tag.append(' ').append(qName).append("=\"").append(xmlEscape(val)).append('"');
                    }
                    tag.append('>');
                    xml.append(tag).append('\n');
                    stack.push(elem);
                    emittedRoot = true;
                }
                case END_ELEMENT -> {
                    if (!stack.isEmpty()) {
                        xml.append("</").append(stack.pop()).append(">\n");
                    }
                }
                case CDATA -> { /* manifests carry no meaningful CDATA */ }
                default -> { /* STRING_POOL already handled; RESOURCE_MAP parsed lazily; skip others */ }
            }
            p = next;
        }
        // close any dangling elements so the regex body matcher still sees balanced tags
        while (!stack.isEmpty()) xml.append("</").append(stack.pop()).append(">\n");

        return emittedRoot ? xml.toString() : null;
    }

    // ---- string pool ---------------------------------------------------------------------

    private void parseStringPool(int off) {
        try {
            long size = u32(off + 4);
            int stringCount = (int) u32(off + 8);
            long flags = u32(off + 16);
            long stringsStart = u32(off + 20);
            boolean utf8 = (flags & 0x100) != 0;

            if (stringCount < 0 || stringCount > 5_000_000) return;
            String[] out = new String[stringCount];
            int offsetsBase = off + 28;
            int dataBase = (int) (off + stringsStart);
            int poolEnd = (int) Math.min(d.length, off + size);

            for (int i = 0; i < stringCount; i++) {
                out[i] = "";
                int oo = offsetsBase + i * 4;
                if (oo + 4 > d.length) continue;
                long rel = u32(oo);
                int s = (int) (dataBase + rel);
                if (s < 0 || s >= poolEnd) continue;
                try {
                    out[i] = utf8 ? readUtf8(s, poolEnd) : readUtf16(s, poolEnd);
                } catch (Exception ignore) { /* keep "" for a corrupt entry, continue */ }
            }
            this.strings = out;

            // resource map (optional) usually follows the string pool
            int rmOff = findChunk(RESOURCE_MAP, off + (int) size >= d.length ? off + 8 : off + (int) size);
            if (rmOff < 0) rmOff = findChunk(RESOURCE_MAP, 8);
            if (rmOff >= 0) parseResourceMap(rmOff);
        } catch (Exception ignore) { /* leave whatever we parsed */ }
    }

    private void parseResourceMap(int off) {
        try {
            long size = u32(off + 4);
            int count = (int) ((size - 8) / 4);
            if (count < 0 || count > 1_000_000) return;
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) {
                int o = off + 8 + i * 4;
                if (o + 4 > d.length) { ids[i] = 0; continue; }
                ids[i] = u32(o);
            }
            this.resourceMap = ids;
        } catch (Exception ignore) {}
    }

    private String readUtf16(int s, int end) {
        int p = s;
        int len = u16(p); p += 2;
        if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | u16(p); p += 2; }
        int byteLen = len * 2;
        if (p + byteLen > end) byteLen = Math.max(0, end - p);
        return new String(d, p, byteLen, StandardCharsets.UTF_16LE);
    }

    private String readUtf8(int s, int end) {
        int p = s;
        // first: number of characters (modified-UTF length), then number of bytes
        p = skipUtf8Len(p);            // char count (unused)
        int[] r = utf8Len(p);
        int byteLen = r[0]; p = r[1];
        if (p + byteLen > end) byteLen = Math.max(0, end - p);
        return new String(d, p, byteLen, StandardCharsets.UTF_8);
    }

    private int skipUtf8Len(int p) { return utf8Len(p)[1]; }

    /** Reads an AXML UTF-8 length prefix; returns {value, nextOffset}. */
    private int[] utf8Len(int p) {
        int b0 = d[p] & 0xff;
        if ((b0 & 0x80) != 0) {
            int b1 = d[p + 1] & 0xff;
            return new int[]{((b0 & 0x7f) << 8) | b1, p + 2};
        }
        return new int[]{b0, p + 1};
    }

    // ---- attribute helpers ---------------------------------------------------------------

    private boolean isAndroidAttr(int nameRef) {
        if (nameRef < 0 || nameRef >= resourceMap.length) return false;
        long id = resourceMap[nameRef];
        return (id & 0xffff0000L) == 0x01010000L || (id >>> 24) == 0x01;
    }

    private String resolveAttrName(int nameRef, int idx, int attrCount) {
        String s = str(nameRef);
        if (s != null && !s.isEmpty()) return s;
        // no string name → resolve via resource id
        if (nameRef >= 0 && nameRef < resourceMap.length) {
            long id = resourceMap[nameRef];
            String known = ATTR_IDS.get(id);
            if (known != null) return known;
            if (id != 0) return "attr_0x" + Long.toHexString(id);
        }
        return null;
    }

    private String attrValue(int dataType, long dataVal, int rawRef) {
        switch (dataType) {
            case TYPE_STRING:      { String s = str(rawRef >= 0 ? rawRef : (int) dataVal); return s == null ? "" : s; }
            case TYPE_INT_BOOLEAN: return dataVal != 0 ? "true" : "false";
            case TYPE_INT_HEX:     return "0x" + Long.toHexString(dataVal & 0xffffffffL);
            case TYPE_REFERENCE:   return "@0x" + Long.toHexString(dataVal & 0xffffffffL);
            case TYPE_INT_DEC:     return Long.toString((int) dataVal);
            default:
                String raw = str(rawRef);
                if (raw != null && !raw.isEmpty()) return raw;
                return Long.toString((int) dataVal);
        }
    }

    // ---- chunk scanning + primitives -----------------------------------------------------

    /** Find the first chunk of {@code type} at or after {@code from}, tolerating junk between chunks. */
    private int findChunk(int type, int from) {
        int p = Math.max(8, from);
        int guard = 0;
        while (p + 8 <= d.length) {
            int t = u16(p);
            long size = u32(p + 4);
            if (t == type && size >= 8 && p + size <= d.length) return p;
            if (size >= 8 && p + size <= d.length) { p += size; }
            else { p = nextChunk(p + 4); if (p < 0) break; }
            if (++guard > 5_000_000) break;
        }
        return -1;
    }

    /** Heuristic: next offset whose u16 looks like a known chunk type. */
    private int nextChunk(int from) {
        for (int p = Math.max(8, from); p + 8 <= d.length; p += 2) { // chunks are 4-byte aligned; step 2 to be safe
            int t = u16(p);
            if (t == STRING_POOL || t == START_NS || t == END_NS || t == START_ELEMENT
                    || t == END_ELEMENT || t == CDATA || t == RESOURCE_MAP) {
                long size = u32(p + 4);
                if (size >= 8 && p + size <= d.length + 0) return p;
            }
        }
        return -1;
    }

    private String str(int ref) {
        if (ref < 0 || ref >= strings.length) return null;
        return strings[ref];
    }

    private int u16(int o) { return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8); }
    private long u32(int o) {
        return (d[o] & 0xffL) | ((d[o + 1] & 0xffL) << 8) | ((d[o + 2] & 0xffL) << 16) | ((d[o + 3] & 0xffL) << 24);
    }
    private int i32(int o) { return (int) u32(o); }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default  -> { if (c >= 0x20 || c == '\t') b.append(c); }
            }
        }
        return b.toString();
    }
}
