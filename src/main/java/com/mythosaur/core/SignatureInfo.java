package com.mythosaur.core;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK signing information — the signer certificate (read directly from META-INF in pure
 * Java) plus signature-scheme verification (v1/v2/v3) via {@code apksigner} when present.
 * Produces a human-readable report for the Overview panel.
 */
public final class SignatureInfo {

    private SignatureInfo() {}

    /** Build a multi-line signing report for the APK. */
    public static String report(File apk) {
        StringBuilder sb = new StringBuilder();

        // 1) signature schemes via apksigner (authoritative on v1/v2/v3 verification)
        String scheme = apksignerSchemes(apk);
        if (scheme != null) sb.append(scheme).append('\n');

        // 2) signer certificate(s) from META-INF (works for v1 / JAR signing)
        boolean foundCert = false;
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int signer = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName().toUpperCase();
                if (!(n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))))
                    continue;
                try (InputStream in = zip.getInputStream(e)) {
                    Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(in);
                    for (java.security.cert.Certificate c : certs) {
                        if (!(c instanceof X509Certificate x)) continue;
                        foundCert = true;
                        sb.append("\nSigner #").append(++signer)
                          .append("  (").append(e.getName()).append(")\n");
                        sb.append("  Subject : ").append(x.getSubjectX500Principal().getName()).append('\n');
                        sb.append("  Issuer  : ").append(x.getIssuerX500Principal().getName()).append('\n');
                        sb.append("  Serial  : ").append(x.getSerialNumber().toString(16)).append('\n');
                        sb.append("  Valid   : ").append(x.getNotBefore()).append("  →  ").append(x.getNotAfter()).append('\n');
                        sb.append("  SigAlg  : ").append(x.getSigAlgName()).append('\n');
                        sb.append("  Key     : ").append(keyInfo(x)).append('\n');
                        sb.append("  SHA-256 : ").append(fingerprint(x, "SHA-256")).append('\n');
                        sb.append("  SHA-1   : ").append(fingerprint(x, "SHA-1")).append('\n');
                    }
                }
            }
        } catch (Exception ex) {
            sb.append("\n(could not read META-INF certificate: ").append(ex.getMessage()).append(")\n");
        }

        if (!foundCert && scheme == null) {
            return "No v1 (JAR) signature certificate found in META-INF, and apksigner is not "
                    + "available to check v2/v3 schemes. The APK may be unsigned or signed only "
                    + "with the v2/v3 APK Signature Scheme.";
        }
        if (!foundCert) {
            sb.append("\n(No v1/JAR certificate in META-INF — signed with v2/v3 scheme only; "
                    + "see scheme verification above.)\n");
        }
        return sb.toString().trim();
    }

    private static String keyInfo(X509Certificate x) {
        var pk = x.getPublicKey();
        if (pk instanceof RSAPublicKey r) return "RSA " + r.getModulus().bitLength() + "-bit";
        if (pk instanceof ECPublicKey e) return "EC " + e.getParams().getCurve().getField().getFieldSize() + "-bit";
        return pk.getAlgorithm();
    }

    private static String fingerprint(X509Certificate x, String algo) {
        try {
            byte[] d = MessageDigest.getInstance(algo).digest(x.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (Exception e) { return "?"; }
    }

    /** Parse `apksigner verify` scheme lines; null if apksigner is unavailable. */
    private static String apksignerSchemes(File apk) {
        if (which("apksigner") == null) return null;
        StringBuilder out = new StringBuilder();
        try {
            Process p = new ProcessBuilder("apksigner", "verify", "-v", apk.getAbsolutePath())
                    .redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            boolean verifies = text.contains("Verifies");
            out.append("Verification: ").append(verifies ? "VERIFIES ✓" : "DOES NOT VERIFY ✗").append('\n');
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.startsWith("Verified using v")) out.append("  ").append(t).append('\n');
            }
        } catch (Exception e) {
            return null;
        }
        return out.length() == 0 ? null : out.toString().trim();
    }

    private static String which(String tool) {
        try {
            Process p = new ProcessBuilder("which", tool).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(3, TimeUnit.SECONDS);
            return (p.exitValue() == 0 && !o.isEmpty()) ? o : null;
        } catch (Exception e) { return null; }
    }
}
