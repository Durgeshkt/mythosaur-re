package com.mythosaur.ui;

import com.mythosaur.core.PermissionAnalyzer;
import com.mythosaur.core.PermissionAnalyzer.Level;
import com.mythosaur.core.PermissionAnalyzer.Perm;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Permission abuse map — a flow chart linking each requested permission to where the app
 * actually exercises it. Dangerous permissions are red, abuse-prone (overlay, install,
 * accessibility, …) orange; declared-but-unused dangerous permissions (over-privilege)
 * are dashed/grey; APIs used without a declared permission are flagged. Click a usage node
 * to open that class.
 */
public class PermissionsView extends JPanel {

    private final Project project;
    private final Consumer<String> openClass;
    private final Chart chart = new Chart();
    private final JLabel summary = new JLabel("Analyzing permissions…");

    public PermissionsView(Project project, Consumer<String> openClass) {
        this.project = project;
        this.openClass = openClass;
        setLayout(new BorderLayout());
        setBackground(Theme.BG);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Theme.SURFACE);
        top.setBorder(new EmptyBorder(6, 10, 6, 10));
        summary.setFont(Theme.sans(12));
        summary.setForeground(Theme.TEXT);
        JButton refresh = new JButton("Re-analyze");
        refresh.addActionListener(e -> run());
        top.add(summary, BorderLayout.CENTER);
        top.add(refresh, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(chart), BorderLayout.CENTER);
    }

    public void run() {
        summary.setText("Analyzing permissions…");
        new SwingWorker<PermissionAnalyzer.Report, Void>() {
            protected PermissionAnalyzer.Report doInBackground() { return project.getPermissionAnalyzer().analyze(); }
            protected void done() {
                try { show(get()); }
                catch (Exception ex) { summary.setText("Permission analysis failed: " + ex.getMessage()); }
            }
        }.execute();
    }

    private void show(PermissionAnalyzer.Report r) {
        int abused = (int) r.permissions.stream().filter(Perm::abused).count();
        summary.setText("<html><b>" + r.permissions.size() + "</b> permissions &nbsp; · &nbsp; "
                + "<font color='#ff5555'>" + r.dangerousCount + " dangerous</font> &nbsp; · &nbsp; "
                + "<font color='#ffa657'>" + r.abuseProneCount + " abuse-prone</font> &nbsp; · &nbsp; "
                + "<font color='#ff5555'>" + abused + " abused (used in code)</font> &nbsp; · &nbsp; "
                + r.overPrivilegedCount + " over-privileged &nbsp; · &nbsp; "
                + r.usedUndeclared + " used-undeclared "
                + "<font color='#8b949e'>[" + project.getManifest().getSource() + " manifest]</font></html>");
        chart.setData(r.permissions);
    }

    // ---------------- Java2D flow chart ----------------

    private final class Chart extends JPanel {
        private List<Perm> perms = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();   // clickable usage-class boxes
        private static final int LX = 24, LW = 240, RX = 320, RW = 360, ROW = 26, PAD = 14, MAXUSE = 7, MIN_BAND = 50;

        Chart() {
            setBackground(Theme.BG);
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    for (Hit h : hits) if (h.rect.contains(e.getPoint()) && h.cls != null) openClass.accept(h.cls);
                }
            });
        }

        void setData(List<Perm> p) {
            this.perms = p;
            int h = PAD;
            for (Perm perm : p) h += bandFor(perm) + PAD;
            setPreferredSize(new Dimension(RX + RW + 40, Math.max(h, 200)));
            revalidate();
            repaint();
        }

        private int rowsFor(Perm perm) {
            int u = Math.min(perm.usingClasses.size(), MAXUSE) + (perm.usingClasses.size() > MAXUSE ? 1 : 0);
            return Math.max(1, u);
        }

        /** Vertical space for one permission band — enough for usage rows AND the 2-line node. */
        private int bandFor(Perm perm) { return Math.max(rowsFor(perm) * ROW, MIN_BAND); }

        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            hits.clear();
            g.setColor(Theme.BG);
            g.fillRect(0, 0, getWidth(), getHeight());

            int y = PAD;
            for (Perm perm : perms) {
                int rows = rowsFor(perm);
                int bandH = bandFor(perm);
                int permCy = y + bandH / 2;
                int usageTop = y + (bandH - rows * ROW) / 2;   // center usage rows in the band

                // left: permission node (tall enough for name + tag, both clearly visible)
                Color c = colorFor(perm);
                int boxY = y + 4, boxH = bandH - 8;
                drawBox(g, LX, boxY, LW, boxH, c, perm.overPrivileged());
                g.setColor(Theme.TEXT);
                g.setFont(Theme.monoBold(13));
                g.drawString(perm.shortName, LX + 12, boxY + 19);
                String tag = tagFor(perm);
                if (!tag.isEmpty()) {
                    g.setColor(tagColor(perm));
                    g.setFont(Theme.mono(11));
                    g.drawString(tag, LX + 12, boxY + 37);
                }

                // right: usage class nodes (capped)
                List<String> classes = new ArrayList<>(perm.usingClasses);
                int shown = Math.min(classes.size(), MAXUSE);
                if (classes.isEmpty()) {
                    g.setColor(Theme.MUTED);
                    g.setFont(Theme.mono(11));
                    g.drawString(perm.declared ? "— not used in code (over-privilege?)" : "—", RX, permCy + 4);
                } else {
                    for (int i = 0; i < shown; i++) {
                        int ry = usageTop + i * ROW;
                        Rectangle rect = new Rectangle(RX, ry + 2, RW, ROW - 4);
                        g.setColor(edgeColor(perm));
                        g.drawLine(LX + LW, permCy, RX, ry + ROW / 2);
                        g.setColor(Theme.SURFACE);
                        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 8, 8);
                        g.setColor(Theme.BORDER);
                        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 8, 8);
                        g.setColor(Theme.CYAN);
                        g.setFont(Theme.mono(11));
                        g.drawString(shorten(classes.get(i), 52), rect.x + 8, rect.y + 15);
                        hits.add(new Hit(rect, classes.get(i)));
                    }
                    if (classes.size() > MAXUSE) {
                        int ry = usageTop + shown * ROW;
                        g.setColor(Theme.MUTED);
                        g.setFont(Theme.mono(11));
                        g.drawString("+" + (classes.size() - MAXUSE) + " more class(es)", RX + 8, ry + 15);
                    }
                }
                y += bandH + PAD;
            }
        }

        private Color tagColor(Perm p) {
            if (!p.declared) return Theme.ORANGE;          // used-undeclared
            if (p.abused()) return Theme.RED;
            if (p.overPrivileged()) return Theme.MUTED;
            if (p.level == Level.DANGEROUS) return Theme.RED;
            if (p.level == Level.ABUSE_PRONE) return Theme.ORANGE;
            if (p.level == Level.SIGNATURE) return Theme.PURPLE;
            return Theme.MUTED;
        }

        private void drawBox(Graphics2D g, int x, int y, int w, int h, Color c, boolean dashed) {
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
            g.fillRoundRect(x, y, w, h, 10, 10);
            Stroke old = g.getStroke();
            if (dashed) g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    8f, new float[]{5f, 4f}, 0f));
            else g.setStroke(new BasicStroke(2f));
            g.setColor(c);
            g.drawRoundRect(x, y, w, h, 10, 10);
            g.setStroke(old);
        }

        private Color colorFor(Perm p) {
            if (p.level == Level.DANGEROUS) return Theme.RED;
            if (p.level == Level.ABUSE_PRONE) return Theme.ORANGE;
            if (p.level == Level.SIGNATURE) return Theme.PURPLE;
            return Theme.MUTED;
        }
        private Color edgeColor(Perm p) { return p.abused() ? Theme.RED : Theme.BORDER; }

        private String tagFor(Perm p) {
            if (!p.declared) return "⚠ used, NOT declared";
            if (p.abused()) return "● ABUSED (used)";
            if (p.overPrivileged()) return "declared, unused";
            if (p.level == Level.DANGEROUS) return "dangerous";
            if (p.level == Level.ABUSE_PRONE) return "abuse-prone";
            return "";
        }
    }

    private record Hit(Rectangle rect, String cls) {}

    private static String shorten(String s, int max) {
        if (s.length() <= max) return s;
        return "…" + s.substring(s.length() - (max - 1));
    }
}
