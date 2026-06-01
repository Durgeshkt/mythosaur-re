package com.mythosaur.ui;

import com.mythosaur.core.ProtectionAnalyzer;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Shows the {@link ProtectionAnalyzer} report: packer/DEX-wrapper, obfuscation,
 * anti-RE checks, and encrypted payloads. Rendered as a styled HTML report.
 *
 * <p>All colours are derived from {@link Theme} at render time so the report stays
 * readable in both the dark and light themes (semantics: red = bad, green = clean,
 * orange = warning).
 */
public class ProtectionsPanel extends JPanel {

    private final Project project;
    private final JEditorPane pane = new JEditorPane();

    public ProtectionsPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());
        setBackground(Theme.BG);

        pane.setContentType("text/html");
        pane.setEditable(false);
        pane.setBackground(Theme.BG);

        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);

        JButton refresh = new JButton("Analyze Protections");
        refresh.addActionListener(e -> run());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(refresh);
        add(bar, BorderLayout.NORTH);

        pane.setText(wrap("<p style='color:" + MUTED() + "'>Click <b>Analyze Protections</b> to scan for packers, obfuscation, and anti-RE.</p>"));
    }

    public void run() {
        pane.setText(wrap("<p style='color:" + MUTED() + "'>Analyzing…</p>"));
        new SwingWorker<ProtectionAnalyzer.Report, Void>() {
            protected ProtectionAnalyzer.Report doInBackground() { return ProtectionAnalyzer.analyze(project); }
            protected void done() {
                try { render(get()); pane.setCaretPosition(0); }
                catch (Exception ex) { pane.setText(wrap("<p style='color:" + RED() + "'>Failed: " + ex.getMessage() + "</p>")); }
            }
        }.execute();
    }

    private void render(ProtectionAnalyzer.Report r) {
        StringBuilder h = new StringBuilder();

        // Packer / DEX wrapper
        h.append(card(r.packed ? RED() : GREEN(),
                "DEX Wrapper / Packer",
                r.packed ? r.packerName + "  <span style='color:" + MUTED() + "'>(" + r.packerConfidence + " confidence)</span>"
                         : (r.dynamicDexLoading
                            ? "No classic packer, but runtime DEX loading detected"
                            : "Not packed — classes.dex is directly analyzable")));
        if (!r.packerIndicators.isEmpty()) h.append(list(r.packerIndicators, MUTED()));
        if (r.packed) h.append("<p style='color:" + ORANGE() + ";font-size:11px'>⚠ Some/all real code is loaded or decrypted at runtime — to see it, dump the loaded DEX from memory (e.g. a runtime DEX dumper) rather than reading classes.dex statically.</p>");

        // Obfuscation
        String obfColor = r.obfuscationPercent >= 70 ? RED() : r.obfuscationPercent >= 40 ? ORANGE() : r.obfuscationPercent > 0 ? AMBER() : GREEN();
        h.append(card(obfColor, "Obfuscation",
                r.obfuscationPercent + "% &nbsp; <span style='color:" + MUTED() + "'>(" + r.obfuscator + ")</span>"));
        if (!r.obfuscationIndicators.isEmpty()) h.append(list(r.obfuscationIndicators, MUTED()));
        if (r.obfuscationPercent >= 40) h.append("<p style='color:" + MUTED() + ";font-size:11px'>Tip: load a ProGuard <code>mapping.txt</code> (File → Load ProGuard Mapping) to restore original names.</p>");

        // Anti-RE
        boolean anyAnti = !r.antiFrida.isEmpty() || !r.antiRoot.isEmpty() || !r.antiDebug.isEmpty() || !r.antiEmulator.isEmpty();
        h.append(card(anyAnti ? RED() : GREEN(), "Anti-Reverse-Engineering",
                anyAnti ? "Detected — the app actively resists analysis" : "None detected"));
        h.append(antiRow("Frida detection", r.antiFrida));
        h.append(antiRow("Root detection", r.antiRoot));
        h.append(antiRow("Debugger detection", r.antiDebug));
        h.append(antiRow("Emulator detection", r.antiEmulator));

        // Encrypted payloads
        h.append(card(r.encryptedPayloads.isEmpty() ? GREEN() : RED(), "Encrypted / Hidden Payloads",
                r.encryptedPayloads.isEmpty() ? "None found (entropy scan)" : r.encryptedPayloads.size() + " suspicious file(s)"));
        if (!r.encryptedPayloads.isEmpty()) {
            h.append("<table style='font-family:monospace;font-size:11px;color:" + TEXT() + "'>");
            h.append("<tr style='color:" + MUTED() + "'><td>FILE</td><td>&nbsp;ENTROPY</td><td>&nbsp;SIZE</td></tr>");
            for (ProtectionAnalyzer.EncryptedFile f : r.encryptedPayloads) {
                h.append("<tr><td>").append(f.name).append("</td><td style='color:" + RED() + "'>&nbsp;")
                 .append(String.format("%.2f", f.entropy)).append("</td><td>&nbsp;").append(f.size).append("</td></tr>");
                h.append("<tr><td colspan='3' style='color:" + ORANGE() + "'>&nbsp;&nbsp;").append(f.note).append("</td></tr>");
            }
            h.append("</table>");
        }

        pane.setText(wrap(h.toString()));
    }

    private String card(String color, String title, String value) {
        return "<div style='margin:10px 0'>"
                + "<span style='color:" + color + ";font-weight:bold;font-size:13px'>● " + title + "</span><br>"
                + "<span style='color:" + TEXT() + ";font-size:13px'>&nbsp;&nbsp;&nbsp;" + value + "</span></div>";
    }

    private String antiRow(String label, List<String> hits) {
        if (hits.isEmpty()) return "";
        return "<div style='margin-left:14px'><span style='color:" + RED() + ";font-size:12px'>✗ " + label + ":</span> "
                + "<span style='color:" + MUTED() + ";font-family:monospace;font-size:11px'>" + String.join(", ", hits) + "</span></div>";
    }

    private String list(List<String> items, String color) {
        StringBuilder sb = new StringBuilder("<ul style='color:" + color + ";font-size:11px;margin-top:0'>");
        for (String i : items) sb.append("<li>").append(escape(i)).append("</li>");
        return sb.append("</ul>").toString();
    }

    private String escape(String s) { return s.replace("<", "&lt;").replace(">", "&gt;"); }

    private String wrap(String body) {
        return "<html><body style='background:" + hex(Theme.BG) + ";color:" + hex(Theme.TEXT)
                + ";font-family:sans-serif;padding:8px'>" + body + "</body></html>";
    }

    // ---- theme-derived report colours (computed at render time for live theme switching) ----
    private static String RED()    { return hex(Theme.RED); }
    private static String GREEN()  { return hex(Theme.GREEN); }
    private static String ORANGE() { return hex(Theme.ORANGE); }
    private static String AMBER()  { return hex(Theme.ACCENT); }
    private static String TEXT()   { return hex(Theme.TEXT); }
    private static String MUTED()  { return hex(Theme.MUTED); }

    private static String hex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
