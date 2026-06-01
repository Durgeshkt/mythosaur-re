package com.mythosaur.ui;

import com.mythosaur.core.DexAnalyzer;
import com.mythosaur.core.Project;
import com.mythosaur.core.XrefEngine;
import com.mythosaur.util.Theme;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bottom panel: callers (who calls the selected method) and callees (what it calls).
 * Double-click navigates to the relevant class in the CodeView.
 */
public class XrefPanel extends JPanel {

    private final Project project;
    private final Consumer<String> onOpenClass;
    private final JLabel header = new JLabel("Select a method to view cross-references");
    private final CallTableModel callersModel = new CallTableModel(true);
    private final CallTableModel calleesModel = new CallTableModel(false);

    public XrefPanel(Project project, Consumer<String> onOpenClass) {
        this.project = project;
        this.onOpenClass = onOpenClass;
        setLayout(new BorderLayout());

        header.setFont(Theme.mono(12));
        header.setForeground(Theme.ACCENT);
        header.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        add(header, BorderLayout.NORTH);

        JTable callers = makeTable(callersModel);
        JTable callees = makeTable(calleesModel);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Callers", new JScrollPane(callers));
        tabs.addTab("Callees", new JScrollPane(callees));
        add(tabs, BorderLayout.CENTER);
    }

    public void showFor(String className, String methodName) {
        XrefEngine xe = project.getXrefEngine();
        List<XrefEngine.Call> callers = xe.callers(className, methodName);
        List<XrefEngine.Call> callees = xe.callees(className, methodName);
        header.setText(DexAnalyzer.simpleType(className) + "." + methodName
                + "()   —  " + callers.size() + " callers, " + callees.size() + " callees");
        callersModel.set(callers);
        calleesModel.set(callees);
    }

    private JTable makeTable(CallTableModel model) {
        JTable table = new JTable(model);
        table.setFont(Theme.mono(12));
        table.setRowHeight(20);
        table.setShowGrid(false);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) onOpenClass.accept(model.targetClass(row));
                }
            }
        });
        return table;
    }

    private static final class CallTableModel extends AbstractTableModel {
        private final boolean callersMode;
        private List<XrefEngine.Call> rows = List.of();
        CallTableModel(boolean callersMode) { this.callersMode = callersMode; }

        void set(List<XrefEngine.Call> r) { rows = r; fireTableDataChanged(); }

        String targetClass(int row) {
            XrefEngine.Call c = rows.get(row);
            return callersMode ? c.fromClass : c.toClass;
        }

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 2; }
        public String getColumnName(int c) { return c == 0 ? "Class" : "Method"; }
        public Object getValueAt(int r, int c) {
            XrefEngine.Call call = rows.get(r);
            if (callersMode) {
                return c == 0 ? DexAnalyzer.simpleType(call.fromClass) : call.fromMethod + "()";
            } else {
                return c == 0 ? DexAnalyzer.simpleType(call.toClass) : call.toMethod + "()";
            }
        }
    }
}
