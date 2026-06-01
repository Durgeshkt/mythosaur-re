package com.mythosaur.ui;

import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resource browser — a tree of everything jadx decoded from the APK (res/, assets/,
 * AndroidManifest, resources.arsc, …). Selecting a node previews it: decoded text/XML for
 * resources, an image preview for drawables, or a size note for raw binary.
 */
public class ResourcesTree extends JPanel {

    private final Project project;
    private final JPanel preview = new JPanel(new BorderLayout());
    private final RSyntaxTextArea textArea = new RSyntaxTextArea();

    public ResourcesTree(Project project) {
        this.project = project;
        setLayout(new BorderLayout());
        setBackground(Theme.BG);

        JTree tree = new JTree(new DefaultTreeModel(buildTree(null)));
        tree.setBackground(Theme.BG);
        tree.setForeground(Theme.TEXT);
        tree.setRootVisible(true);

        JTextField filter = new JTextField();
        filter.setFont(Theme.mono(12));
        filter.setToolTipText("Filter resources by name/path");
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void refilter() {
                String f = filter.getText().trim().toLowerCase();
                tree.setModel(new DefaultTreeModel(buildTree(f.isEmpty() ? null : f)));
                for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
        });

        textArea.setEditable(false);
        textArea.setFont(Theme.mono(12));
        textArea.setBackground(Theme.BG);
        textArea.setForeground(Theme.TEXT);

        preview.setBackground(Theme.BG);
        setPreview(new JLabel("  Select a resource to preview", SwingConstants.LEFT));

        tree.addTreeSelectionListener(e -> {
            Object node = tree.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Leaf leaf) {
                show(leaf.rf);
            }
        });

        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.setBackground(Theme.BG);
        left.add(filter, BorderLayout.NORTH);
        left.add(new JScrollPane(tree), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, preview);
        split.setResizeWeight(0.34);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
    }

    private record Leaf(ResourceFile rf, String label) {
        public String toString() { return label; }
    }

    private DefaultMutableTreeNode buildTree(String filter) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("resources");
        Map<String, DefaultMutableTreeNode> dirs = new HashMap<>();
        dirs.put("", root);
        var resources = project.getJadx().getResources();
        resources.sort((a, b) -> a.getOriginalName().compareToIgnoreCase(b.getOriginalName()));
        for (ResourceFile rf : resources) {
            String path = rf.getOriginalName();
            if (filter != null && !path.toLowerCase().contains(filter)) continue;
            String[] parts = path.split("/");
            DefaultMutableTreeNode parent = root;
            StringBuilder acc = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                acc.append(parts[i]).append('/');
                String key = acc.toString();
                DefaultMutableTreeNode dir = dirs.get(key);
                if (dir == null) {
                    dir = new DefaultMutableTreeNode(parts[i]);
                    parent.add(dir);
                    dirs.put(key, dir);
                }
                parent = dir;
            }
            parent.add(new DefaultMutableTreeNode(new Leaf(rf, parts[parts.length - 1])));
        }
        return root;
    }

    /** Background-loaded result; Swing components are built later on the EDT in {@link #render}. */
    private record Loaded(java.awt.image.BufferedImage image, String text, String syntax, String message) {}

    private void show(ResourceFile rf) {
        String name = rf.getOriginalName();
        setPreview(new JLabel("  Loading " + name + "…"));
        new SwingWorker<Loaded, Void>() {
            protected Loaded doInBackground() { return load(rf, name); }   // I/O only, no Swing
            protected void done() {
                try { setPreview(render(get())); }                        // build UI on the EDT
                catch (Exception ex) { setPreview(new JLabel("  failed: " + ex.getMessage())); }
            }
        }.execute();
    }

    /** Off-EDT: read + decode the resource into plain data (no Swing component touched here). */
    private Loaded load(ResourceFile rf, String name) {
        String lower = name.toLowerCase();
        // images / binary: read raw bytes from the APK (jadx keeps binary XML decoded as text,
        // but real images are best read straight from the package)
        if (rf.getType() == ResourceType.IMG || lower.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) {
            byte[] data = readApkEntry(name);
            if (data != null) {
                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
                    if (img != null) return new Loaded(img, null, null, null);
                } catch (Exception ignored) {}
                return new Loaded(null, null, null, "  image (" + data.length + " bytes) — preview unavailable");
            }
        }
        // text / xml / arsc via jadx's decoder
        try {
            var rc = rf.loadContent();
            String text = rc != null && rc.getText() != null ? rc.getText().getCodeStr() : null;
            if (text != null && !text.isEmpty()) return new Loaded(null, text, syntaxFor(lower), null);
            byte[] data = readApkEntry(name);
            return new Loaded(null, null, null, "  binary resource — " + (data != null ? data.length + " bytes" : "no preview"));
        } catch (Exception e) {
            return new Loaded(null, null, null, "  could not decode: " + e.getMessage());
        }
    }

    /** EDT only: turn the loaded data into a Swing preview component. */
    private Component render(Loaded l) {
        if (l.image != null) {
            JLabel lbl = new JLabel(new ImageIcon(l.image));
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            lbl.setVerticalAlignment(SwingConstants.CENTER);
            return new JScrollPane(lbl);
        }
        if (l.text != null) {
            textArea.setSyntaxEditingStyle(l.syntax);
            textArea.setText(l.text);
            textArea.setCaretPosition(0);
            return new RTextScrollPane(textArea);
        }
        return new JLabel(l.message != null ? l.message : "  no preview");
    }

    private static String syntaxFor(String lower) {
        if (lower.endsWith(".xml")) return SyntaxConstants.SYNTAX_STYLE_XML;
        if (lower.endsWith(".json")) return SyntaxConstants.SYNTAX_STYLE_JSON;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return SyntaxConstants.SYNTAX_STYLE_HTML;
        if (lower.endsWith(".js")) return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    private byte[] readApkEntry(String name) {
        try (ZipFile zip = new ZipFile(project.getApkFile())) {
            ZipEntry e = zip.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) { return in.readAllBytes(); }
        } catch (Exception ex) { return null; }
    }

    private void setPreview(Component c) {
        preview.removeAll();
        preview.add(c, BorderLayout.CENTER);
        preview.revalidate();
        preview.repaint();
    }
}
