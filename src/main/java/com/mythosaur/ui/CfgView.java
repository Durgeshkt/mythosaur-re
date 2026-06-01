package com.mythosaur.ui;

import com.mythosaur.core.CfgBuilder;
import com.mythosaur.util.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.geom.CubicCurve2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Method Control Flow Graph — Cutter's signature "graph view". Renders a method's
 * basic blocks (the dalvik instructions split at branch boundaries) as a top-down
 * flowchart: green = branch-taken, red = branch-not-taken, amber = goto/fall,
 * cyan = switch case, purple = loop back-edge.
 */
public class CfgView extends JPanel {

    private CfgBuilder.Cfg cfg;
    private final Map<Integer, Rectangle> boxes = new HashMap<>();
    private final Map<Integer, Integer> level = new HashMap<>();

    private double panX = 0, panY = 20, zoom = 1.0;
    private int lastX, lastY;

    private static final int LINE_H = 15, PAD = 8, HEADER_H = 18;
    private static final int LEVEL_GAP = 50, COL_GAP = 40, CHAR_W = 7;

    public CfgView() {
        setBackground(Theme.BG);
        Mouse m = new Mouse();
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    public void show(CfgBuilder.Cfg cfg) {
        this.cfg = cfg;
        doLayout2();
        panX = 0; panY = 20; zoom = 1.0;
        repaint();
    }

    private void doLayout2() {
        boxes.clear();
        level.clear();
        if (cfg == null || cfg.blocks.isEmpty()) return;

        // forward predecessors (exclude back edges)
        Map<Integer, List<Integer>> fwdPreds = new HashMap<>();
        for (CfgBuilder.Block b : cfg.blocks) fwdPreds.put(b.id, new ArrayList<>());
        for (CfgBuilder.Edge e : cfg.edges) {
            if (e.kind != CfgBuilder.EdgeKind.BACK) fwdPreds.get(e.to).add(e.from);
        }
        // longest-path levels via relaxation (forward graph is acyclic)
        for (CfgBuilder.Block b : cfg.blocks) level.put(b.id, 0);
        for (int iter = 0; iter < cfg.blocks.size(); iter++) {
            boolean changed = false;
            for (CfgBuilder.Block b : cfg.blocks) {
                int max = 0;
                for (int p : fwdPreds.get(b.id)) max = Math.max(max, level.get(p) + 1);
                if (max != level.get(b.id)) { level.put(b.id, max); changed = true; }
            }
            if (!changed) break;
        }

        // group by level
        Map<Integer, List<CfgBuilder.Block>> byLevel = new HashMap<>();
        int maxLevel = 0;
        for (CfgBuilder.Block b : cfg.blocks) {
            int lv = level.get(b.id);
            byLevel.computeIfAbsent(lv, k -> new ArrayList<>()).add(b);
            maxLevel = Math.max(maxLevel, lv);
        }

        // place: compute level heights and box sizes
        int y = 0;
        for (int lv = 0; lv <= maxLevel; lv++) {
            List<CfgBuilder.Block> row = byLevel.getOrDefault(lv, List.of());
            int rowHeight = 0;
            int totalWidth = 0;
            List<Dimension> sizes = new ArrayList<>();
            for (CfgBuilder.Block b : row) {
                int w = blockWidth(b), h = blockHeight(b);
                sizes.add(new Dimension(w, h));
                rowHeight = Math.max(rowHeight, h);
                totalWidth += w + COL_GAP;
            }
            int x = -totalWidth / 2;
            for (int i = 0; i < row.size(); i++) {
                Dimension d = sizes.get(i);
                boxes.put(row.get(i).id, new Rectangle(x, y, d.width, d.height));
                x += d.width + COL_GAP;
            }
            y += rowHeight + LEVEL_GAP;
        }
    }

    private int blockWidth(CfgBuilder.Block b) {
        int max = b.label().length();
        for (CfgBuilder.Insn in : b.insns) max = Math.max(max, in.text.length() + 7);
        return Math.min(420, Math.max(120, max * CHAR_W + 2 * PAD));
    }

    private int blockHeight(CfgBuilder.Block b) {
        return HEADER_H + b.insns.size() * LINE_H + PAD;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (cfg == null || cfg.blocks.isEmpty()) {
            g2.setColor(Theme.MUTED);
            g2.setFont(Theme.sans(13));
            String msg = "Double-click a method (Methods table / Xrefs) to view its control-flow graph";
            int w = g2.getFontMetrics().stringWidth(msg);
            g2.drawString(msg, (getWidth() - w) / 2, getHeight() / 2);
            g2.dispose();
            return;
        }

        g2.translate(getWidth() / 2.0 + panX, panY);
        g2.scale(zoom, zoom);

        for (CfgBuilder.Edge e : cfg.edges) drawEdge(g2, e);
        for (CfgBuilder.Block b : cfg.blocks) drawBlock(g2, b);

        g2.dispose();

        // control hint (screen space)
        g.setColor(Theme.MUTED);
        g.setFont(Theme.sans(10));
        g.drawString("drag: pan   ·   scroll: zoom   ·   double-click: reset", 10, getHeight() - 8);
    }

    private void resetView() { panX = 0; panY = 20; zoom = 1.0; repaint(); }

    private Color edgeColor(CfgBuilder.EdgeKind k) {
        return switch (k) {
            case BRANCH_TRUE -> Theme.GREEN;
            case BRANCH_FALSE -> Theme.RED;
            case SWITCH -> Theme.CYAN;
            case BACK -> Theme.PURPLE;
            default -> Theme.ACCENT;
        };
    }

    private void drawEdge(Graphics2D g2, CfgBuilder.Edge e) {
        Rectangle a = boxes.get(e.from), b = boxes.get(e.to);
        if (a == null || b == null) return;
        Color c = edgeColor(e.kind);
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 0xAA));
        g2.setStroke(new BasicStroke(1.5f));

        if (e.kind == CfgBuilder.EdgeKind.BACK) {
            double x1 = a.x, y1 = a.y + a.height / 2.0;
            double x2 = b.x, y2 = b.y + b.height / 2.0;
            double bend = 50;
            g2.draw(new CubicCurve2D.Double(x1, y1, x1 - bend, y1, x2 - bend, y2, x2, y2));
            g2.fillPolygon(new int[]{(int) x2, (int) x2 - 8, (int) x2 - 8},
                    new int[]{(int) y2, (int) y2 - 4, (int) y2 + 4}, 3);
        } else {
            double x1 = a.x + a.width / 2.0, y1 = a.y + a.height;
            double x2 = b.x + b.width / 2.0, y2 = b.y;
            g2.draw(new CubicCurve2D.Double(x1, y1, x1, y1 + 30, x2, y2 - 30, x2, y2));
            g2.fillPolygon(new int[]{(int) x2, (int) x2 - 5, (int) x2 + 5},
                    new int[]{(int) y2, (int) y2 - 8, (int) y2 - 8}, 3);
        }
    }

    private void drawBlock(Graphics2D g2, CfgBuilder.Block b) {
        Rectangle r = boxes.get(b.id);
        if (r == null) return;
        boolean entry = b.id == 0;

        g2.setColor(Theme.SURFACE);
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g2.setColor(entry ? Theme.ACCENT : Theme.BORDER);
        g2.setStroke(new BasicStroke(entry ? 2f : 1f));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);

        // header
        g2.setColor(entry ? Theme.ACCENT : Theme.CYAN);
        g2.setFont(Theme.monoBold(11));
        g2.drawString((entry ? "▶ " : "") + b.label(), r.x + PAD, r.y + 13);

        // instructions
        g2.setFont(Theme.mono(11));
        int y = r.y + HEADER_H + 11;
        for (CfgBuilder.Insn in : b.insns) {
            g2.setColor(Theme.MUTED);
            g2.drawString(hex4(in.addr), r.x + PAD, y);
            g2.setColor(Theme.TEXT);
            String t = in.text;
            int maxChars = (r.width - 2 * PAD - 6 * CHAR_W) / CHAR_W;
            if (t.length() > maxChars && maxChars > 4) t = t.substring(0, maxChars - 1) + "…";
            g2.drawString(t, r.x + PAD + 6 * CHAR_W, y);
            y += LINE_H;
        }
    }

    private static String hex4(int a) {
        String h = Integer.toHexString(a);
        return "    ".substring(Math.min(4, h.length())) + h;
    }

    private final class Mouse extends MouseAdapter {
        @Override public void mousePressed(java.awt.event.MouseEvent e) { lastX = e.getX(); lastY = e.getY(); }
        @Override public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) resetView();   // double-click → reset pan/zoom
        }
        @Override public void mouseDragged(java.awt.event.MouseEvent e) {
            panX += e.getX() - lastX; panY += e.getY() - lastY;
            lastX = e.getX(); lastY = e.getY();
            repaint();
        }
        @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
            double f = e.getWheelRotation() < 0 ? 1.1 : 0.9;
            zoom = Math.max(0.3, Math.min(2.5, zoom * f));
            repaint();
        }
    }
}
