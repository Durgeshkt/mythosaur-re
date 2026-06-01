package com.mythosaur.ui;

import com.mythosaur.core.DexAnalyzer;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Cutter-style analysis: tabbed tables of Classes / Methods / Imports / Strings,
 * straight from dexlib2. Filter box per tab, double-click navigates to source.
 */
public class AnalysisPanel extends JPanel {

    private final Project project;
    private final Consumer<String> onOpenClass; // full class name
    private final BiConsumer<String, String> onSelectMethod; // (class, method)

    public AnalysisPanel(Project project, Consumer<String> onOpenClass,
                         BiConsumer<String, String> onSelectMethod) {
        this.project = project;
        this.onOpenClass = onOpenClass;
        this.onSelectMethod = onSelectMethod;
        setLayout(new BorderLayout());

        DexAnalyzer an = project.getAnalyzer();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Classes (" + an.getClasses().size() + ")", classesTab(an));
        tabs.addTab("Methods (" + an.getMethods().size() + ")", methodsTab(an));
        tabs.addTab("Imports (" + an.getImports().size() + ")", importsTab(an));
        tabs.addTab("Strings (" + an.getStrings().size() + ")", stringsTab(an));
        add(tabs, BorderLayout.CENTER);
    }

    // ---- Classes ----
    private JComponent classesTab(DexAnalyzer an) {
        List<DexAnalyzer.ClassInfo> rows = an.getClasses();
        AbstractTableModel model = new AbstractTableModel() {
            final String[] cols = {"Access", "Class", "Extends", "Methods"};
            public int getRowCount() { return rows.size(); }
            public int getColumnCount() { return cols.length; }
            public String getColumnName(int c) { return cols[c]; }
            public Object getValueAt(int r, int c) {
                DexAnalyzer.ClassInfo ci = rows.get(r);
                return switch (c) {
                    case 0 -> ci.accessFlags;
                    case 1 -> ci.name;
                    case 2 -> ci.superName.equals("java.lang.Object") ? "" : DexAnalyzer.simpleType(ci.superName);
                    default -> ci.methodCount;
                };
            }
        };
        JTable table = makeTable(model);
        onDoubleClick(table, row -> onOpenClass.accept(rows.get(row).name));
        return wrapWithFilter(table);
    }

    // ---- Methods ----
    private JComponent methodsTab(DexAnalyzer an) {
        return methodTable(an.getMethods());
    }

    private JComponent importsTab(DexAnalyzer an) {
        return methodTable(an.getImports());
    }

    private JComponent methodTable(List<DexAnalyzer.MethodInfo> rows) {
        AbstractTableModel model = new AbstractTableModel() {
            final String[] cols = {"Return", "Class", "Method", "Params"};
            public int getRowCount() { return rows.size(); }
            public int getColumnCount() { return cols.length; }
            public String getColumnName(int c) { return cols[c]; }
            public Object getValueAt(int r, int c) {
                DexAnalyzer.MethodInfo m = rows.get(r);
                return switch (c) {
                    case 0 -> m.returnType;
                    case 1 -> DexAnalyzer.simpleType(m.className);
                    case 2 -> m.name;
                    default -> String.join(", ", m.params);
                };
            }
        };
        JTable table = makeTable(model);
        onDoubleClick(table, row -> {
            DexAnalyzer.MethodInfo m = rows.get(row);
            onOpenClass.accept(m.className);
            if (onSelectMethod != null) onSelectMethod.accept(m.className, m.name);
        });
        return wrapWithFilter(table);
    }

    // ---- Strings ----
    private JComponent stringsTab(DexAnalyzer an) {
        List<String> rows = an.getStrings();
        AbstractTableModel model = new AbstractTableModel() {
            public int getRowCount() { return rows.size(); }
            public int getColumnCount() { return 1; }
            public String getColumnName(int c) { return "String"; }
            public Object getValueAt(int r, int c) { return rows.get(r); }
        };
        JTable table = makeTable(model);
        return wrapWithFilter(table);
    }

    // ---- helpers ----
    private JTable makeTable(AbstractTableModel model) {
        JTable table = new JTable(model);
        table.setFont(Theme.mono(12));
        table.setRowHeight(20);
        table.setAutoCreateRowSorter(true);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setFont(Theme.sans(11));
        return table;
    }

    private void onDoubleClick(JTable table, Consumer<Integer> action) {
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = table.getSelectedRow();
                    if (viewRow >= 0) action.accept(table.convertRowIndexToModel(viewRow));
                }
            }
        });
    }

    private JComponent wrapWithFilter(JTable table) {
        JPanel panel = new JPanel(new BorderLayout());
        JTextField filter = new JTextField();
        filter.putClientProperty("JTextField.placeholderText", "Filter…");
        filter.setFont(Theme.mono(12));

        @SuppressWarnings("unchecked")
        TableRowSorter<AbstractTableModel> sorter =
                (TableRowSorter<AbstractTableModel>) table.getRowSorter();

        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void apply() {
                String text = filter.getText();
                if (text.isBlank()) sorter.setRowFilter(null);
                else sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { apply(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { apply(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { apply(); }
        });

        panel.add(filter, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }
}
