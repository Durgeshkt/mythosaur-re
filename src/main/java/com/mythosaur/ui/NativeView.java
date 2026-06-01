package com.mythosaur.ui;

import com.mythosaur.core.ElfFile;
import com.mythosaur.core.NativeAnalyzer;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;

/**
 * Native library (.so) analysis — like a lightweight Ghidra/Cutter native view.
 * Left: every .so in the APK. Right: tabs for Info, JNI functions, Exports,
 * Imports, Strings, plus on-demand objdump disassembly.
 */
public class NativeView extends JPanel {

    private final Project project;
    private final NativeAnalyzer analyzer;
    private final JTabbedPane detail = new JTabbedPane();
    private String currentEntry;
    private ElfFile currentElf;

    public NativeView(Project project) {
        this.project = project;
        this.analyzer = project.getNativeAnalyzer();
        setLayout(new BorderLayout());

        List<String> sos = analyzer.listSoEntries();
        DefaultListModel<String> model = new DefaultListModel<>();
        sos.forEach(model::addElement);
        JList<String> list = new JList<>(model);
        list.setFont(Theme.mono(12));
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                loadSo(list.getSelectedValue());
            }
        });

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(BorderFactory.createTitledBorder(".so libraries (" + sos.size() + ")"));
        listScroll.setPreferredSize(new Dimension(260, 100));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detail);
        split.setResizeWeight(0.28);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        if (!sos.isEmpty()) {
            list.setSelectedIndex(0);
        } else {
            detail.addTab("Info", centered("No native libraries (.so) in this APK"));
        }
    }

    private void loadSo(String entry) {
        currentEntry = entry;
        detail.removeAll();
        try {
            currentElf = analyzer.parse(entry);
        } catch (Exception ex) {
            detail.addTab("Error", centered("Failed to parse: " + ex.getMessage()));
            return;
        }
        if (!currentElf.isValid()) {
            detail.addTab("Info", centered("Not a valid ELF: " + entry));
            return;
        }
        detail.addTab("Info", infoTab());
        detail.addTab("JNI (" + currentElf.getJniFunctions().size() + ")", jniTab());
        detail.addTab("Exports (" + currentElf.getExports().size() + ")", symbolTab(currentElf.getExports()));
        detail.addTab("Imports (" + currentElf.getImports().size() + ")", symbolTab(currentElf.getImports()));
        detail.addTab("Strings (" + currentElf.getStrings().size() + ")", stringsTab());
        detail.addTab("Disassembly", disasmTab());
    }

    private JComponent infoTab() {
        StringBuilder sb = new StringBuilder();
        sb.append("File:        ").append(currentEntry).append('\n');
        sb.append("Arch:        ").append(currentElf.getArch()).append('\n');
        sb.append("Class:       ").append(currentElf.is64Bit() ? "ELF64" : "ELF32").append('\n');
        sb.append("Exports:     ").append(currentElf.getExports().size()).append('\n');
        sb.append("Imports:     ").append(currentElf.getImports().size()).append('\n');
        sb.append("JNI funcs:   ").append(currentElf.getJniFunctions().size()).append('\n');
        sb.append("Strings:     ").append(currentElf.getStrings().size()).append('\n');
        sb.append("\nSections:\n");
        sb.append(String.format("  %-20s %-10s %12s %12s%n", "NAME", "TYPE", "ADDR", "SIZE"));
        for (ElfFile.Section s : currentElf.getSections()) {
            if (s.name.isEmpty()) continue;
            sb.append(String.format("  %-20s %-10d %12x %12d%n", s.name, s.type, s.addr, s.size));
        }
        return textArea(sb.toString());
    }

    private JComponent jniTab() {
        List<ElfFile.Symbol> jni = currentElf.getJniFunctions();
        if (jni.isEmpty()) return centered("No JNI functions exported.\n(no Java_* or JNI_OnLoad symbols)");
        AbstractTableModel m = new AbstractTableModel() {
            final String[] cols = {"Address", "JNI Function (native method)"};
            public int getRowCount() { return jni.size(); }
            public int getColumnCount() { return 2; }
            public String getColumnName(int c) { return cols[c]; }
            public Object getValueAt(int r, int c) {
                ElfFile.Symbol s = jni.get(r);
                return c == 0 ? "0x" + Long.toHexString(s.value) : demangleJni(s.name);
            }
        };
        return tableScroll(m);
    }

    private JComponent symbolTab(List<ElfFile.Symbol> syms) {
        AbstractTableModel m = new AbstractTableModel() {
            final String[] cols = {"Address", "Size", "Type", "Symbol"};
            public int getRowCount() { return syms.size(); }
            public int getColumnCount() { return 4; }
            public String getColumnName(int c) { return cols[c]; }
            public Object getValueAt(int r, int c) {
                ElfFile.Symbol s = syms.get(r);
                return switch (c) {
                    case 0 -> s.value != 0 ? "0x" + Long.toHexString(s.value) : "";
                    case 1 -> s.size;
                    case 2 -> s.function ? "FUNC" : "DATA";
                    default -> s.name;
                };
            }
        };
        return tableScroll(m);
    }

    private JComponent stringsTab() {
        List<String> strs = currentElf.getStrings();
        AbstractTableModel m = new AbstractTableModel() {
            public int getRowCount() { return strs.size(); }
            public int getColumnCount() { return 1; }
            public String getColumnName(int c) { return "String"; }
            public Object getValueAt(int r, int c) { return strs.get(r); }
        };
        return tableScroll(m);
    }

    private JComponent disasmTab() {
        JPanel panel = new JPanel(new BorderLayout());
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        area.setEditable(false);
        area.setFont(Theme.mono(12));
        area.setBackground(Theme.BG);
        area.setForeground(Theme.TEXT);

        // Pick a disassembler that actually targets this .so's architecture (ARM/AArch64
        // libs need a cross- or multi-arch objdump, not the stock x86 one).
        NativeAnalyzer.Disassembler tool = analyzer.pickDisassembler(currentElf);
        area.setText(tool != null
                ? "Click 'Disassemble (" + tool.exe() + ")' to disassemble this "
                        + currentElf.getArch() + " library."
                : analyzer.installHint(currentElf));

        JButton run = new JButton(tool != null
                ? "Disassemble (" + tool.exe() + ")"
                : "No disassembler for " + currentElf.getArch());
        run.setEnabled(tool != null);
        final String entry = currentEntry;
        run.addActionListener(e -> {
            area.setText("Disassembling " + entry + " …");
            new SwingWorker<String, Void>() {
                protected String doInBackground() throws Exception {
                    return analyzer.disassemble(entry, 0);
                }
                protected void done() {
                    try { area.setText(get()); area.setCaretPosition(0); }
                    catch (Exception ex) { area.setText("Disassembly failed: " + ex.getMessage()); }
                }
            }.execute();
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(run);
        panel.add(bar, BorderLayout.NORTH);
        panel.add(new RTextScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    // ---- helpers ----
    private JComponent tableScroll(AbstractTableModel m) {
        JTable t = new JTable(m);
        t.setFont(Theme.mono(12));
        t.setRowHeight(20);
        t.setAutoCreateRowSorter(true);
        t.setShowGrid(false);
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(null);
        return sp;
    }

    private JComponent textArea(String text) {
        JTextArea ta = new JTextArea(text);
        ta.setEditable(false);
        ta.setFont(Theme.mono(12));
        ta.setBackground(Theme.BG);
        ta.setForeground(Theme.TEXT);
        JScrollPane sp = new JScrollPane(ta);
        sp.setBorder(null);
        return sp;
    }

    private JComponent centered(String msg) {
        JLabel l = new JLabel("<html><div style='text-align:center'>" + msg.replace("\n", "<br>") + "</div></html>",
                SwingConstants.CENTER);
        l.setForeground(Theme.MUTED);
        return l;
    }

    /** Java_com_app_Foo_bar -> com.app.Foo.bar (readable native method name). */
    private static String demangleJni(String n) {
        if (n.equals("JNI_OnLoad")) return "JNI_OnLoad";
        if (!n.startsWith("Java_")) return n;
        String body = n.substring(5);
        // last '_' separates class path from method (best-effort; JNI mangling has edge cases)
        int last = body.lastIndexOf('_');
        if (last < 0) return body.replace('_', '.');
        String cls = body.substring(0, last).replace('_', '.');
        String method = body.substring(last + 1);
        return cls + "." + method + "()";
    }
}
