package com.mythosaur;

import com.mythosaur.core.AxmlDecoder;

import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Debug: dump the tolerant-decoded AndroidManifest.xml of a (clean/normalized) APK. */
public class AxmlDump {
    public static void main(String[] args) throws Exception {
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry e = zip.getEntry("AndroidManifest.xml");
            byte[] bytes;
            try (InputStream in = zip.getInputStream(e)) { bytes = in.readAllBytes(); }
            String xml = AxmlDecoder.decode(bytes);
            System.out.println(xml);
            long up = xml == null ? 0 : xml.lines().filter(l -> l.contains("<uses-permission")).count();
            long act = xml == null ? 0 : xml.lines().filter(l -> l.contains("<activity")).count();
            long svc = xml == null ? 0 : xml.lines().filter(l -> l.contains("<service")).count();
            long rcv = xml == null ? 0 : xml.lines().filter(l -> l.contains("<receiver")).count();
            System.err.println("### uses-permission=" + up + " activity=" + act + " service=" + svc + " receiver=" + rcv);
        }
    }
}
