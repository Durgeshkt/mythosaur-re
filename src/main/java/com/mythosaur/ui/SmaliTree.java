package com.mythosaur.ui;

import com.mythosaur.util.Theme;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;

/** File tree over the disassembled smali directory. Clicking a .smali file opens it editable. */
public class SmaliTree extends JPanel {

    private final Consumer<File> onOpenSmali;

    public SmaliTree(File smaliRoot, Consumer<File> onOpenSmali) {
        this.onOpenSmali = onOpenSmali;
        setLayout(new BorderLayout());

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new FileRef(smaliRoot));
        buildNodes(root, smaliRoot, 0);

        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Theme.sans(12));
        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof FileRef ref
                    && ref.file.isFile() && ref.file.getName().endsWith(".smali")) {
                onOpenSmali.accept(ref.file);
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
    }

    private record FileRef(File file) {
        @Override public String toString() { return file.getName(); }
    }

    private void buildNodes(DefaultMutableTreeNode parent, File dir, int depth) {
        File[] children = dir.listFiles();
        if (children == null || depth > 40) return;
        Arrays.sort(children, Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase()));
        for (File child : children) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new FileRef(child));
            parent.add(node);
            if (child.isDirectory()) buildNodes(node, child, depth + 1);
        }
    }
}
