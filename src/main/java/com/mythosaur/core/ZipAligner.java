package com.mythosaur.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Pure-Java equivalent of {@code zipalign -f 4} — so the patch pipeline needs no external
 * zipalign binary. Uncompressed (STORED) entries are aligned so their data starts on a
 * {@code alignment}-byte boundary (Android requires this for memory-mapped access to
 * {@code resources.arsc} and uncompressed native libs); compressed entries are copied as-is.
 *
 * <p>Entry compression methods are preserved exactly, so an aligned {@code resources.arsc}
 * stays STORED. {@link com.android.apksig.ApkSigner} preserves this byte layout when it signs,
 * so aligning here and signing in-process gives a correctly aligned, signed APK.
 */
public final class ZipAligner {

    private ZipAligner() {}

    public static void align(File in, File out) throws IOException {
        align(in, out, 4);
    }

    public static void align(File in, File out, int alignment) throws IOException {
        try (ZipFile zf = new ZipFile(in);
             CountingOutputStream counting = new CountingOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(out.toPath())));
             ZipOutputStream zos = new ZipOutputStream(counting)) {

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry src = entries.nextElement();
                ZipEntry dst = new ZipEntry(src.getName());
                dst.setMethod(src.getMethod());
                if (src.getTime() != -1) dst.setTime(src.getTime());

                if (src.getMethod() == ZipEntry.STORED) {
                    dst.setSize(src.getSize());
                    dst.setCompressedSize(src.getSize());
                    dst.setCrc(src.getCrc());
                    // Pad the local-header extra field so the entry's data starts aligned.
                    // Local file header = 30 bytes + name + extra; pad against the byte
                    // offset where this header begins.
                    long headerStart = counting.count;
                    int nameLen = dst.getName().getBytes(StandardCharsets.UTF_8).length;
                    long dataStart = headerStart + 30L + nameLen;
                    int pad = (int) ((alignment - (dataStart % alignment)) % alignment);
                    if (pad > 0) dst.setExtra(new byte[pad]);
                }

                zos.putNextEntry(dst);
                try (InputStream is = zf.getInputStream(src)) {
                    is.transferTo(zos);
                }
                zos.closeEntry();
            }
        }
    }

    /** Counts bytes written so we know where each local file header begins. */
    private static final class CountingOutputStream extends FilterOutputStream {
        long count = 0;
        CountingOutputStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); count += len;
        }
    }
}
