package com.mythosaur.ui;

import com.mythosaur.core.ManifestParser;
import com.mythosaur.core.Project;
import com.mythosaur.core.SignatureInfo;
import com.mythosaur.util.Theme;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * APK Overview — the at-a-glance summary a reverse engineer wants first: file identity
 * and hash, package / version / SDK levels, component & permission counts, code and
 * native stats, and the full signing certificate + scheme verification.
 */
public class OverviewPanel extends JPanel {

    private final Project project;
    private final JEditorPane pane = new JEditorPane();

    public OverviewPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());
        setBackground(Theme.BG);
        pane.setContentType("text/html");
        pane.setEditable(false);
        pane.setBackground(Theme.BG);
        JScrollPane sp = new JScrollPane(pane);
        sp.setBorder(null);
        add(sp, BorderLayout.CENTER);
        pane.setText(wrap("<p style='color:" + hex(Theme.MUTED) + "'>Loading overview…</p>"));
    }

    public void run() {
        new SwingWorker<String, Void>() {
            protected String doInBackground() { return build(); }
            protected void done() {
                try { pane.setText(get()); pane.setCaretPosition(0); }
                catch (Exception ex) { pane.setText(wrap("<p style='color:" + hex(Theme.RED) + "'>Overview failed: " + ex.getMessage() + "</p>")); }
            }
        }.execute();
    }

    private String build() {
        var mf = project.getManifest();
        var an = project.getAnalyzer();
        java.io.File apk = project.getApkFile();

        StringBuilder h = new StringBuilder();
        h.append(section("APK File"));
        h.append(row("Name", apk.getName()));
        h.append(row("Size", human(apk.length())));
        h.append(row("SHA-256", "<span style='font-family:monospace;font-size:11px'>" + sha256(apk) + "</span>"));
        h.append(row("Manifest source", mf.getSource()));

        h.append(section("Application"));
        h.append(row("Package", mf.getPackageName()));
        h.append(row("Version", str(mf.getVersionName()) + "  (code " + str(mf.getVersionCode()) + ")"));
        h.append(row("SDK", "min " + str(mf.getMinSdk()) + " · target " + str(mf.getTargetSdk())
                + " · compile " + str(mf.getCompileSdk())));
        if (mf.getApplicationClass() != null) h.append(row("Application class", mf.getApplicationClass()));
        if (mf.getLauncherActivity() != null) h.append(row("Launcher", mf.getLauncherActivity()));

        h.append(section("Components"));
        h.append(row("Activities", String.valueOf(mf.getActivities().size())));
        h.append(row("Services", String.valueOf(mf.getServices().size())));
        h.append(row("Receivers", String.valueOf(mf.getReceivers().size())));
        h.append(row("Providers", String.valueOf(mf.getProviders().size())));
        h.append(row("Permissions", String.valueOf(mf.getPermissions().size())));

        h.append(section("Code"));
        h.append(row("DEX files", String.valueOf(project.getDexFiles().size())));
        h.append(row("Classes", String.valueOf(an.getTotalClasses())));
        h.append(row("Methods", String.valueOf(an.getTotalMethods())));
        h.append(row("Strings", String.valueOf(an.getTotalStrings())));
        var so = project.getNativeAnalyzer().listSoEntries();
        if (!so.isEmpty()) h.append(row("Native libraries", so.size() + "  (.so)"));

        h.append(section("Signature"));
        h.append("<pre style='color:" + hex(Theme.TEXT) + ";font-family:monospace;font-size:11px;white-space:pre-wrap'>"
                + escape(SignatureInfo.report(apk)) + "</pre>");

        return wrap(h.toString());
    }

    private static String section(String title) {
        return "<div style='color:" + hex(Theme.CYAN) + ";font-weight:bold;font-size:14px;margin:16px 0 6px'>" + title + "</div>";
    }

    private static String row(String k, String v) {
        return "<div style='font-size:12px;margin:3px 0'>"
                + "<span style='color:" + hex(Theme.MUTED) + ";display:inline-block;width:140px'>" + k + "</span>"
                + "<span style='color:" + hex(Theme.TEXT) + "'>" + (v == null ? "—" : v) + "</span></div>";
    }

    private static String str(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0; if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0; return String.format("%.1f MB", mb);
    }

    private static String sha256(java.io.File f) {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return "?"; }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String wrap(String body) {
        return "<html><body style='background:" + hex(Theme.BG) + ";color:" + hex(Theme.TEXT)
                + ";font-family:sans-serif;padding:10px'>" + body + "</body></html>";
    }

    private static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }
}
