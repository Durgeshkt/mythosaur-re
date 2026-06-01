package com.mythosaur.ui;

import com.mythosaur.core.DexAnalyzer;
import com.mythosaur.core.FlowAnalyzer;
import com.mythosaur.core.ManifestParser;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.geom.CubicCurve2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * APP NAVIGATION FLOW — the headline feature.
 *
 * A top-down flowchart of the whole app: the launcher Activity at the top, with
 * arrows to every Activity it opens (via explicit Intents), laid out in BFS levels.
 * This shows how the app actually flows screen-to-screen — something no other
 * Android RE tool provides. Click a box to open its decompiled source.
 */
public class AppFlowView extends JPanel {

    private final Project project;
    private final Consumer<String> onOpenClass;

    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Node> byName = new HashMap<>();
    private List<FlowAnalyzer.Edge> edges = List.of();

    private double panX = 40, panY = 30, zoom = 1.0;
    private double initPanX = 0, initPanY = 24, initZoom = 1.0;  // auto-fit view from build(), for reset
    private int lastX, lastY;
    private Node dragNode = null;       // node being dragged (null → panning the canvas)
    private boolean dragged = false;    // distinguishes a drag from a click
    private String hovered = null;
    private double minX, maxX, minY, maxY;

    private static final int BOX_W = 190, BOX_H = 44;
    private static final int LEVEL_GAP = 90, COL_GAP = 36;

    public AppFlowView(Project project, Consumer<String> onOpenClass) {
        this.project = project;
        this.onOpenClass = onOpenClass;
        setBackground(Theme.BG);
        Mouse mouse = new Mouse();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private static final class Node {
        final String name, simple;
        final boolean launcher, exported;
        int level;
        double x, y;
        Node(String name, boolean launcher, boolean exported) {
            this.name = name; this.launcher = launcher; this.exported = exported;
            int dot = name.lastIndexOf('.');
            this.simple = dot >= 0 ? name.substring(dot + 1) : name;
        }
    }

    /** Build the flow graph from the manifest + flow analyzer. Call on a worker thread. */
    public void build() {
        try {
            ManifestParser mf = project.getManifest();
            FlowAnalyzer fa = project.getFlowAnalyzer();
            this.edges = fa.getEdges();

            nodes.clear();
            byName.clear();
            for (ManifestParser.Component c : mf.getActivities()) {
                Node n = new Node(c.name, c.launcher, c.exported);
                nodes.add(n);
                byName.put(c.name, n);
            }
            // also create nodes for any edge endpoint not in the manifest activity list
            for (FlowAnalyzer.Edge e : edges) {
                if (!byName.containsKey(e.from)) addNode(e.from);
                if (!byName.containsKey(e.to)) addNode(e.to);
            }
            layout(mf.getLauncherActivity(), fa);
            // deterministic initial view (no dependency on panel size at paint time):
            // graph is laid out centred on x=0, so panX=0 + paint's getWidth()/2 centres it.
            double gw = Math.max(1, maxX - minX);
            zoom = Math.max(0.4, Math.min(1.1, 980.0 / gw));
            panX = 0;
            panY = 24;
            initPanX = panX; initPanY = panY; initZoom = zoom;   // remember for "reset view"
        } catch (Throwable t) {
            t.printStackTrace();
        }
        SwingUtilities.invokeLater(this::repaint);
    }

    private void resetView() { panX = initPanX; panY = initPanY; zoom = initZoom; repaint(); }

    private void addNode(String name) {
        Node n = new Node(name, false, false);
        nodes.add(n);
        byName.put(name, n);
    }

    private void layout(String launcher, FlowAnalyzer fa) {
        // BFS levels from launcher
        Map<String, Integer> level = new HashMap<>();
        Queue<String> q = new ArrayDeque<>();
        if (launcher != null && byName.containsKey(launcher)) {
            level.put(launcher, 0);
            q.add(launcher);
        }
        while (!q.isEmpty()) {
            String cur = q.poll();
            int curLvl = level.get(cur);
            for (String to : fa.targetsOf(cur)) {
                if (!byName.containsKey(to)) continue;
                if (!level.containsKey(to)) {
                    level.put(to, curLvl + 1);
                    q.add(to);
                }
            }
        }
        // Orphans (not reachable from launcher) go to a trailing level
        int maxLvl = level.values().stream().mapToInt(i -> i).max().orElse(-1);
        int orphanLvl = maxLvl + 1;
        for (Node n : nodes) {
            if (!level.containsKey(n.name)) level.put(n.name, orphanLvl);
        }

        // group by level
        Map<Integer, List<Node>> byLevel = new java.util.TreeMap<>();
        for (Node n : nodes) {
            n.level = level.get(n.name);
            byLevel.computeIfAbsent(n.level, k -> new ArrayList<>()).add(n);
        }

        // Position each level as a centered GRID (wrap wide levels into sub-rows so a
        // node with 20+ children isn't one unreadable 4000px line). Y advances by each
        // level's actual grid height.
        final int MAX_COLS = 6;
        final int SUBROW_GAP = 18;
        double y = 0;
        minX = Double.MAX_VALUE; maxX = -Double.MAX_VALUE; minY = 0; maxY = 0;
        for (Map.Entry<Integer, List<Node>> e : byLevel.entrySet()) {
            List<Node> row = e.getValue();
            row.sort((a, b) -> a.simple.compareToIgnoreCase(b.simple));
            int count = row.size();
            int cols = Math.min(count, MAX_COLS);
            int rows = (count + cols - 1) / cols;
            double gridW = cols * BOX_W + (cols - 1) * COL_GAP;
            double startX = -gridW / 2.0;
            for (int i = 0; i < count; i++) {
                Node n = row.get(i);
                int col = i % cols, sub = i / cols;
                n.x = startX + col * (BOX_W + COL_GAP);
                n.y = y + sub * (BOX_H + SUBROW_GAP);
                minX = Math.min(minX, n.x); maxX = Math.max(maxX, n.x + BOX_W);
                maxY = Math.max(maxY, n.y + BOX_H);
            }
            y += rows * BOX_H + (rows - 1) * SUBROW_GAP + LEVEL_GAP;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (nodes.isEmpty()) {
            g2.setColor(Theme.MUTED);
            g2.setFont(Theme.sans(13));
            String msg = "Building app flow…  (no activities found in manifest yet)";
            int w = g2.getFontMetrics().stringWidth(msg);
            g2.drawString(msg, (getWidth() - w) / 2, getHeight() / 2);
            g2.dispose();
            return;
        }

        g2.translate(getWidth() / 2.0 + panX, panY);
        g2.scale(zoom, zoom);

        // edges first
        for (FlowAnalyzer.Edge e : edges) {
            Node a = byName.get(e.from), b = byName.get(e.to);
            if (a == null || b == null) continue;
            drawEdge(g2, a, b, e.indirect);
        }
        // nodes
        for (Node n : nodes) drawNode(g2, n);

        g2.dispose();

        // control hint (screen space)
        g.setColor(Theme.MUTED);
        g.setFont(Theme.sans(10));
        g.drawString("click box: open class   ·   drag box: move   ·   drag empty: pan   ·   scroll: zoom   ·   double-click empty: reset",
                10, getHeight() - 8);
    }

    private void drawEdge(Graphics2D g2, Node a, Node b, boolean indirect) {
        double x1 = a.x + BOX_W / 2.0, y1 = a.y + BOX_H;
        double x2 = b.x + BOX_W / 2.0, y2 = b.y;
        boolean backEdge = b.y <= a.y; // same level or upward

        g2.setColor(indirect ? new Color(0x8b, 0x94, 0x9e, 0xCC)
                : backEdge ? new Color(0xbc, 0x8c, 0xff, 0xDD)
                           : new Color(0xf0, 0xa5, 0x00, 0xDD));
        // dashed stroke for inferred (helper-launched) edges; solid + thicker for direct
        g2.setStroke(indirect
                ? new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 4f}, 0f)
                : new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (backEdge) {
            // route from right side, loop down
            x1 = a.x + BOX_W; y1 = a.y + BOX_H / 2.0;
            x2 = b.x + BOX_W; y2 = b.y + BOX_H / 2.0;
            double bend = 60;
            g2.draw(new CubicCurve2D.Double(x1, y1, x1 + bend, y1, x2 + bend, y2, x2, y2));
            arrow(g2, x2, y2, 1);
        } else {
            g2.draw(new CubicCurve2D.Double(x1, y1, x1, y1 + 40, x2, y2 - 40, x2, y2));
            arrow(g2, x2, y2, 0);
        }
    }

    private void arrow(Graphics2D g2, double x, double y, int dir) {
        if (dir == 0) { // pointing down
            g2.fillPolygon(new int[]{(int) x, (int) x - 7, (int) x + 7},
                    new int[]{(int) y, (int) y - 11, (int) y - 11}, 3);
        } else { // pointing left
            g2.fillPolygon(new int[]{(int) x, (int) x + 11, (int) x + 11},
                    new int[]{(int) y, (int) y - 7, (int) y + 7}, 3);
        }
    }

    private void drawNode(Graphics2D g2, Node n) {
        Color fill, border, text;
        if (n.launcher) {
            fill = Theme.ACCENT; border = Theme.ACCENT; text = Theme.BG;
        } else if (n.exported) {
            fill = new Color(0x00, 0xd4, 0xff, 0x22); border = Theme.CYAN; text = Theme.CYAN;
        } else {
            fill = Theme.SURFACE; border = Theme.BORDER; text = Theme.TEXT;
        }
        if (n.name.equals(hovered)) {
            border = Theme.GREEN;
        }

        g2.setColor(fill);
        g2.fillRoundRect((int) n.x, (int) n.y, BOX_W, BOX_H, 10, 10);
        g2.setColor(border);
        g2.setStroke(new BasicStroke(n.launcher ? 2.2f : 1.2f));
        g2.drawRoundRect((int) n.x, (int) n.y, BOX_W, BOX_H, 10, 10);

        g2.setColor(text);
        if (n.launcher) {
            g2.setFont(Theme.sans(9));
            g2.drawString("▶ LAUNCHER", (int) n.x + 10, (int) n.y + 14);
            g2.setFont(Theme.monoBold(12));
            g2.drawString(clip(g2, n.simple, BOX_W - 16), (int) n.x + 10, (int) n.y + 32);
        } else {
            g2.setFont(Theme.mono(12));
            g2.drawString(clip(g2, n.simple, BOX_W - 16), (int) n.x + 10, (int) n.y + 27);
            if (n.exported) {
                g2.setFont(Theme.sans(8));
                g2.setColor(Theme.CYAN);
                g2.drawString("exported", (int) n.x + BOX_W - 52, (int) n.y + 12);
            }
        }
    }

    private String clip(Graphics2D g2, String s, int max) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(s) <= max) return s;
        while (s.length() > 3 && fm.stringWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private Node nodeAt(int sx, int sy) {
        double mx = (sx - getWidth() / 2.0 - panX) / zoom;
        double my = (sy - panY) / zoom;
        for (Node n : nodes) {
            if (mx >= n.x && mx <= n.x + BOX_W && my >= n.y && my <= n.y + BOX_H) return n;
        }
        return null;
    }

    private final class Mouse extends MouseAdapter {
        @Override public void mousePressed(java.awt.event.MouseEvent e) {
            lastX = e.getX(); lastY = e.getY();
            dragged = false;
            dragNode = nodeAt(e.getX(), e.getY());   // grab a node, or null → pan the canvas
            if (dragNode != null) setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        }
        @Override public void mouseDragged(java.awt.event.MouseEvent e) {
            dragged = true;
            double dx = e.getX() - lastX, dy = e.getY() - lastY;
            if (dragNode != null) {
                // move just this node (convert screen delta to graph space); edges follow
                dragNode.x += dx / zoom;
                dragNode.y += dy / zoom;
            } else {
                panX += dx; panY += dy;       // empty space → pan the whole map
            }
            lastX = e.getX(); lastY = e.getY();
            repaint();
        }
        @Override public void mouseReleased(java.awt.event.MouseEvent e) {
            dragNode = null;
            setCursor(nodeAt(e.getX(), e.getY()) != null
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        }
        @Override public void mouseMoved(java.awt.event.MouseEvent e) {
            Node n = nodeAt(e.getX(), e.getY());
            String name = n != null ? n.name : null;
            if (!java.util.Objects.equals(name, hovered)) {
                hovered = name;
                setCursor(n != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                repaint();
            }
        }
        @Override public void mouseClicked(java.awt.event.MouseEvent e) {
            if (dragged) return;             // a drag isn't a click — don't open the class
            Node n = nodeAt(e.getX(), e.getY());
            if (n != null) { onOpenClass.accept(n.name); return; }
            if (e.getClickCount() == 2) resetView();   // double-click empty space → reset view
        }
        @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
            double f = e.getWheelRotation() < 0 ? 1.1 : 0.9;
            zoom = Math.max(0.3, Math.min(2.5, zoom * f));
            repaint();
        }
    }
}
