package com.mythosaur.ui;

import com.mythosaur.core.DexAnalyzer;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;
import jadx.api.JavaClass;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.StringReference;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Global search across the decompiled app: class names, method signatures, the string
 * pool, and — optionally — the <b>full decompiled source</b> of every class. Substring or
 * regex matching. Double-click a result to jump to it (code results scroll to the line;
 * a string is resolved to the first class that references it).
 *
 * <p>Full-text code search decompiles every class, so it is opt-in, streamed, shows
 * progress, and can be stopped.
 */
public class SearchView extends JPanel {

    private static final int CAP = 2000, CODE_CAP = 800, PER_CLASS = 6;

    private final Project project;
    private final Consumer<String> openClass;
    private final BiConsumer<String, Integer> openAtLine;

    private final JTextField query = new JTextField();
    private final JCheckBox cClasses = new JCheckBox("Classes", true);
    private final JCheckBox cMethods = new JCheckBox("Methods", true);
    private final JCheckBox cStrings = new JCheckBox("Strings", true);
    private final JCheckBox cCode = new JCheckBox("Code (full-text)", false);
    private final JCheckBox cRegex = new JCheckBox("Regex", false);
    private final JButton go = new JButton("Search");
    private final JButton stop = new JButton("Stop");
    private final JLabel count = new JLabel(" ");
    private final DefaultTableModel model =
            new DefaultTableModel(new Object[]{"Type", "Match", "Location"}, 0) {
                public boolean isCellEditable(int r, int c) { return false; }
            };
    private final JTable table = new JTable(model);
    private SwingWorker<Integer, Object[]> worker;

    public SearchView(Project project, Consumer<String> openClass, BiConsumer<String, Integer> openAtLine) {
        this.project = project;
        this.openClass = openClass;
        this.openAtLine = openAtLine;
        setLayout(new BorderLayout());
        setBackground(Theme.BG);
        add(buildBar(), BorderLayout.NORTH);

        table.setFont(Theme.mono(12));
        table.setRowHeight(18);
        table.setBackground(Theme.BG);
        table.setForeground(Theme.TEXT);
        table.getColumnModel().getColumn(0).setMaxWidth(64);
        table.getColumnModel().getColumn(1).setPreferredWidth(320);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) navigate(table.getSelectedRow());
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    private JComponent buildBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(Theme.SURFACE);
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        query.setFont(Theme.mono(13));
        query.setToolTipText("Type a query and press Enter");
        query.addActionListener(e -> search());

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        opts.setBackground(Theme.SURFACE);
        for (JCheckBox cb : new JCheckBox[]{cClasses, cMethods, cStrings, cCode, cRegex}) {
            cb.setBackground(Theme.SURFACE); cb.setForeground(Theme.TEXT); cb.setFont(Theme.sans(11));
            opts.add(cb);
        }
        cCode.setToolTipText("Search the full decompiled source of every class (slower)");
        go.addActionListener(e -> search());
        stop.addActionListener(e -> { if (worker != null) worker.cancel(true); });
        stop.setEnabled(false);
        opts.add(go);
        opts.add(stop);
        count.setForeground(Theme.MUTED); count.setFont(Theme.mono(11));
        opts.add(count);

        bar.add(new JLabel("🔍"), BorderLayout.WEST);
        bar.add(query, BorderLayout.CENTER);
        bar.add(opts, BorderLayout.EAST);
        return bar;
    }

    public void focusQuery() { query.requestFocusInWindow(); query.selectAll(); }

    private void search() {
        if (worker != null && !worker.isDone()) worker.cancel(true);
        String q = query.getText().trim();
        model.setRowCount(0);
        if (q.isEmpty()) { count.setText(" "); return; }
        final Predicate<String> match = matcher(q, cRegex.isSelected());
        if (match == null) { count.setForeground(Theme.RED); count.setText("invalid regex"); return; }

        final boolean wantClasses = cClasses.isSelected(), wantMethods = cMethods.isSelected(),
                wantStrings = cStrings.isSelected(), wantCode = cCode.isSelected();
        count.setForeground(Theme.MUTED); count.setText("searching…");
        go.setEnabled(false); stop.setEnabled(true);

        worker = new SwingWorker<Integer, Object[]>() {
            int total = 0;
            protected Integer doInBackground() {
                var an = project.getAnalyzer();
                if (wantClasses)
                    for (var c : an.getClasses()) { if (isCancelled()) return total; if (match.test(c.name)) if (add("class", c.name, c.name)) return total; }
                if (wantMethods)
                    for (var m : an.getMethods()) { if (isCancelled()) return total; if (match.test(m.name) || match.test(m.signature())) if (add("method", m.name + "()", m.className)) return total; }
                if (wantStrings)
                    for (var s : an.getStrings()) { if (isCancelled()) return total; if (match.test(s)) if (add("string", s, "(double-click → find usage)")) return total; }
                if (wantCode) scanCode(match);
                return total;
            }
            boolean add(String type, String matchTxt, String loc) {
                publish(new Object[]{type, matchTxt, loc});
                return ++total >= CAP;
            }
            void scanCode(Predicate<String> match) {
                List<JavaClass> classes = project.getJadx().getClasses();
                int n = classes.size(), codeHits = 0;
                for (int i = 0; i < n; i++) {
                    if (isCancelled()) return;
                    if ((i & 0x1ff) == 0) publish(new Object[]{"§progress", "scanned " + i + "/" + n + " classes · " + total + " matches"});
                    JavaClass jc = classes.get(i);
                    String code;
                    try { code = jc.getCode(); } catch (Throwable t) { continue; }
                    if (code == null || code.isEmpty()) continue;
                    String[] lines = code.split("\n", -1);
                    int perClass = 0;
                    for (int li = 0; li < lines.length; li++) {
                        if (match.test(lines[li])) {
                            publish(new Object[]{"code", lines[li].strip(), jc.getFullName() + ":" + (li + 1)});
                            total++; codeHits++;
                            if (++perClass >= PER_CLASS) break;
                            if (codeHits >= CODE_CAP || total >= CAP) return;
                        }
                    }
                }
            }
            protected void process(List<Object[]> chunks) {
                for (Object[] r : chunks) {
                    if (r.length == 2 && "§progress".equals(r[0])) count.setText((String) r[1]);
                    else model.addRow(r);
                }
            }
            protected void done() {
                go.setEnabled(true); stop.setEnabled(false);
                count.setForeground(Theme.MUTED);
                try {
                    if (isCancelled()) { count.setText(model.getRowCount() + " matches (stopped)"); return; }
                    int n = get();
                    count.setText((n >= CAP ? CAP + "+ (capped)" : n + " match" + (n == 1 ? "" : "es")));
                } catch (Exception e) { count.setText("stopped"); }
            }
        };
        worker.execute();
    }

    private static Predicate<String> matcher(String q, boolean regex) {
        if (regex) {
            try { Pattern p = Pattern.compile(q, Pattern.CASE_INSENSITIVE); return s -> s != null && p.matcher(s).find(); }
            catch (Exception e) { return null; }
        }
        String lower = q.toLowerCase();
        return s -> s != null && s.toLowerCase().contains(lower);
    }

    private void navigate(int row) {
        if (row < 0) return;
        String type = (String) model.getValueAt(row, 0);
        String loc = (String) model.getValueAt(row, 2);
        switch (type) {
            case "class", "method" -> openClass.accept(loc);
            case "code" -> {
                int c = loc.lastIndexOf(':');
                if (c > 0) {
                    try { openAtLine.accept(loc.substring(0, c), Integer.parseInt(loc.substring(c + 1))); return; }
                    catch (NumberFormatException ignored) {}
                }
                openClass.accept(loc);
            }
            case "string" -> findStringUsage((String) model.getValueAt(row, 1));
        }
    }

    /** Locate the first class that references a string literal, then open it. */
    private void findStringUsage(String value) {
        count.setText("locating usage…");
        new SwingWorker<String, Void>() {
            protected String doInBackground() {
                for (DexBackedDexFile dex : project.getDexFiles())
                    for (ClassDef cls : dex.getClasses())
                        for (Method m : cls.getMethods()) {
                            if (m.getImplementation() == null) continue;
                            for (Instruction insn : m.getImplementation().getInstructions())
                                if (insn instanceof ReferenceInstruction ri
                                        && ri.getReference() instanceof StringReference sr
                                        && value.equals(sr.getString()))
                                    return DexAnalyzer.descToDotted(cls.getType());
                        }
                return null;
            }
            protected void done() {
                try {
                    String cls = get();
                    if (cls != null) { openClass.accept(cls); count.setText("opened " + cls); }
                    else count.setText("no code reference (likely a resource/manifest string)");
                } catch (Exception ex) { count.setText("lookup failed"); }
            }
        }.execute();
    }
}
