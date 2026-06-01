package com.mythosaur.ui;

import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;
import jadx.api.JavaClass;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Left panel: package → class tree built from jadx's class list, with a live filter box.
 * Clicking a class opens it in the CodeView. Typing in the filter narrows the tree to
 * classes whose full name matches (case-insensitive) and auto-expands the matches.
 */
public class ProjectTree extends JPanel {

    private final JTree tree;
    private final Consumer<JavaClass> onOpenClass;
    private final List<JavaClass> classes;

    public ProjectTree(Project project, Consumer<JavaClass> onOpenClass) {
        this.onOpenClass = onOpenClass;
        this.classes = project.getClasses();
        setLayout(new BorderLayout());

        tree = new JTree(new DefaultTreeModel(buildRoot(null)));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Theme.sans(12));

        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof ClassRef ref) {
                onOpenClass.accept(ref.javaClass);
            }
        });

        JTextField filter = new JTextField();
        filter.putClientProperty("JTextField.placeholderText", "Filter classes…  (e.g. com.app or Login)");
        filter.setFont(Theme.mono(12));
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void apply() {
                String q = filter.getText().trim().toLowerCase();
                tree.setModel(new DefaultTreeModel(buildRoot(q.isEmpty() ? null : q)));
                if (!q.isEmpty()) expandAll();
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { apply(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { apply(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { apply(); }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(null);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        top.add(filter, BorderLayout.NORTH);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /** Wrapper so tree nodes can carry the JavaClass but display the short name. */
    private record ClassRef(String simpleName, JavaClass javaClass) {
        @Override public String toString() { return simpleName; }
    }

    /** Build the package→class tree, optionally keeping only classes matching {@code filter}. */
    private DefaultMutableTreeNode buildRoot(String filter) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("APK");
        Map<String, DefaultMutableTreeNode> pkgNodes = new HashMap<>();
        pkgNodes.put("", root);

        for (JavaClass cls : classes) {
            if (filter != null && !cls.getFullName().toLowerCase().contains(filter)) continue;
            DefaultMutableTreeNode pkgNode = getOrCreatePackage(root, pkgNodes, cls.getPackage());
            pkgNode.add(new DefaultMutableTreeNode(new ClassRef(cls.getName(), cls)));
        }
        sortChildren(root);
        return root;
    }

    private DefaultMutableTreeNode getOrCreatePackage(DefaultMutableTreeNode root,
                                                      Map<String, DefaultMutableTreeNode> nodes, String pkg) {
        if (pkg == null || pkg.isEmpty()) return root;
        DefaultMutableTreeNode existing = nodes.get(pkg);
        if (existing != null) return existing;

        int lastDot = pkg.lastIndexOf('.');
        String parentPkg = lastDot >= 0 ? pkg.substring(0, lastDot) : "";
        String simple = lastDot >= 0 ? pkg.substring(lastDot + 1) : pkg;
        DefaultMutableTreeNode parent = getOrCreatePackage(root, nodes, parentPkg);

        DefaultMutableTreeNode node = new DefaultMutableTreeNode(simple);
        parent.add(node);
        nodes.put(pkg, node);
        return node;
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    private void sortChildren(DefaultMutableTreeNode node) {
        java.util.List<DefaultMutableTreeNode> children = new java.util.ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            children.add((DefaultMutableTreeNode) node.getChildAt(i));
        }
        children.sort((a, b) -> {
            boolean aLeaf = a.getUserObject() instanceof ClassRef;
            boolean bLeaf = b.getUserObject() instanceof ClassRef;
            if (aLeaf != bLeaf) return aLeaf ? 1 : -1; // packages before classes
            return a.toString().compareToIgnoreCase(b.toString());
        });
        node.removeAllChildren();
        for (DefaultMutableTreeNode c : children) {
            node.add(c);
            sortChildren(c);
        }
    }
}
