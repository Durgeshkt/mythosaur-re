package com.mythosaur.ui;

import com.mythosaur.core.DexAnalyzer;
import com.mythosaur.core.Project;
import com.mythosaur.core.XrefEngine;
import com.mythosaur.util.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.geom.CubicCurve2D;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Cutter-style call-flow graph drawn with Java2D: target method in the center,
 * callers on the left, callees on the right. Click a node to recenter on it.
 * Drag to pan, scroll to zoom.
 */
public class GraphView extends JPanel {

    private final Project project;
    private final Consumer<String> onOpenClass;

    private String rootClass = "";
    private String rootMethod = "";
    private final List<Node> nodes = new ArrayList<>();

    private double panX = 0, panY = 0, zoom = 1.0;
    private int lastX, lastY;

    private static final int NODE_W = 230, NODE_H = 34;
    private static final int COL_GAP = 320, ROW_GAP = 46;

    public GraphView(Project project, Consumer<String> onOpenClass) {
        this.project = project;
        this.onOpenClass = onOpenClass;
        setBackground(Theme.BG);

        MouseAdapterImpl ma = new MouseAdapterImpl();
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    public void showFor(String className, String methodName) {
        this.rootClass = className;
        this.rootMethod = methodName;
        layoutGraph();
        panX = 0; panY = 0; zoom = 1.0;
        repaint();
    }

    private static final class Node {
        final String cls, method, label;
        final int kind; // 0 root, 1 caller, 2 callee
        double x, y;
        Node(String cls, String method, int kind) {
            this.cls = cls; this.method = method; this.kind = kind;
            this.label = DexAnalyzer.simpleType(cls) + "." + method + "()";
        }
    }

    private void layoutGraph() {
        nodes.clear();
        if (rootClass.isEmpty()) return;

        XrefEngine xe = project.getXrefEngine();
        List<XrefEngine.Call> callers = xe.callers(rootClass, rootMethod);
        List<XrefEngine.Call> callees = xe.callees(rootClass, rootMethod);

        Set<String> callerKeys = new LinkedHashSet<>();
        for (XrefEngine.Call c : callers) callerKeys.add(c.fromClass + "\0" + c.fromMethod);
        Set<String> calleeKeys = new LinkedHashSet<>();
        for (XrefEngine.Call c : callees) calleeKeys.add(c.toClass + "\0" + c.toMethod);

        int maxRows = Math.max(Math.max(callerKeys.size(), calleeKeys.size()), 1);
        double centerY = (maxRows - 1) * ROW_GAP / 2.0;

        Node rootNode = new Node(rootClass, rootMethod, 0);
        rootNode.x = COL_GAP;
        rootNode.y = centerY;
        nodes.add(rootNode);

        int i = 0;
        double callerOffset = (maxRows - callerKeys.size()) * ROW_GAP / 2.0;
        for (String k : callerKeys) {
            String[] parts = k.split("\0");
            Node n = new Node(parts[0], parts[1], 1);
            n.x = 0; n.y = i * ROW_GAP + callerOffset;
            nodes.add(n); i++;
        }
        i = 0;
        double calleeOffset = (maxRows - calleeKeys.size()) * ROW_GAP / 2.0;
        for (String k : calleeKeys) {
            String[] parts = k.split("\0");
            Node n = new Node(parts[0], parts[1], 2);
            n.x = COL_GAP * 2; n.y = i * ROW_GAP + calleeOffset;
            nodes.add(n); i++;
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
            String msg = "Select a method (double-click in Methods/Xrefs) to view its call graph";
            int w = g2.getFontMetrics().stringWidth(msg);
            g2.drawString(msg, (getWidth() - w) / 2, getHeight() / 2);
            g2.dispose();
            return;
        }

        g2.translate(40 + panX, 60 + panY);
        g2.scale(zoom, zoom);

        Node root = nodes.get(0);
        // edges
        g2.setStroke(new BasicStroke(1.4f));
        for (Node n : nodes) {
            if (n.kind == 1) drawEdge(g2, n, root);
            else if (n.kind == 2) drawEdge(g2, root, n);
        }
        // nodes
        for (Node n : nodes) drawNode(g2, n);

        g2.dispose();

        // control hint (screen space)
        g.setColor(Theme.MUTED);
        g.setFont(Theme.sans(10));
        g.drawString("drag: pan   ·   scroll: zoom   ·   click node: recenter   ·   double-click empty: reset",
                10, getHeight() - 8);
    }

    private void resetView() { panX = 0; panY = 0; zoom = 1.0; repaint(); }

    private void drawEdge(Graphics2D g2, Node from, Node to) {
        double x1 = from.x + NODE_W, y1 = from.y + NODE_H / 2.0;
        double x2 = to.x, y2 = to.y + NODE_H / 2.0;
        g2.setColor(new Color(0xf0, 0xa5, 0x00, 0x66));
        g2.draw(new CubicCurve2D.Double(x1, y1, x1 + 60, y1, x2 - 60, y2, x2, y2));
        g2.fillPolygon(new int[]{(int) x2, (int) x2 - 8, (int) x2 - 8},
                new int[]{(int) y2, (int) y2 - 4, (int) y2 + 4}, 3);
    }

    private void drawNode(Graphics2D g2, Node n) {
        Color fill, border, text;
        switch (n.kind) {
            case 0 -> { fill = Theme.ACCENT; border = Theme.ACCENT; text = Theme.BG; }
            case 1 -> { fill = new Color(0x00, 0xd4, 0xff, 0x22); border = Theme.CYAN; text = Theme.CYAN; }
            default -> { fill = new Color(0x00, 0xff, 0x88, 0x22); border = Theme.GREEN; text = Theme.GREEN; }
        }
        g2.setColor(fill);
        g2.fillRoundRect((int) n.x, (int) n.y, NODE_W, NODE_H, 8, 8);
        g2.setColor(border);
        g2.setStroke(new BasicStroke(n.kind == 0 ? 2f : 1f));
        g2.drawRoundRect((int) n.x, (int) n.y, NODE_W, NODE_H, 8, 8);

        g2.setColor(text);
        g2.setFont(Theme.mono(11));
        String label = n.label;
        FontMetrics fm = g2.getFontMetrics();
        while (fm.stringWidth(label) > NODE_W - 16 && label.length() > 4) {
            label = label.substring(0, label.length() - 4) + "…";
        }
        g2.drawString(label, (int) n.x + 8, (int) n.y + NODE_H / 2 + 4);
    }

    private Node nodeAt(int sx, int sy) {
        double mx = (sx - 40 - panX) / zoom;
        double my = (sy - 60 - panY) / zoom;
        for (Node n : nodes) {
            if (mx >= n.x && mx <= n.x + NODE_W && my >= n.y && my <= n.y + NODE_H) return n;
        }
        return null;
    }

    private final class MouseAdapterImpl extends MouseAdapter {
        @Override public void mousePressed(java.awt.event.MouseEvent e) {
            lastX = e.getX(); lastY = e.getY();
        }
        @Override public void mouseDragged(java.awt.event.MouseEvent e) {
            panX += e.getX() - lastX; panY += e.getY() - lastY;
            lastX = e.getX(); lastY = e.getY();
            repaint();
        }
        @Override public void mouseClicked(java.awt.event.MouseEvent e) {
            Node n = nodeAt(e.getX(), e.getY());
            if (n == null) {
                if (e.getClickCount() == 2) resetView();   // double-click empty space → reset pan/zoom
                return;
            }
            if (e.getClickCount() == 2) {
                onOpenClass.accept(n.cls);
            } else if (n.kind != 0) {
                showFor(n.cls, n.method); // recenter
            }
        }
        @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
            double factor = e.getWheelRotation() < 0 ? 1.1 : 0.9;
            zoom = Math.max(0.3, Math.min(3.0, zoom * factor));
            repaint();
        }
    }
}
