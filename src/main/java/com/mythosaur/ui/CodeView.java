package com.mythosaur.ui;

import com.mythosaur.core.Decompilers;
import com.mythosaur.util.Theme;
import jadx.api.JavaClass;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Center editor: a tab per opened class, decompiled Java shown in RSyntaxTextArea.
 * Source comes from jadx's JavaClass.getCode() — real decompiler output, on demand.
 */
public class CodeView extends JPanel {

    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<String, RSyntaxTextArea> openTabs = new HashMap<>();
    private final Decompilers decompilers;   // multi-engine hub (may be null → jadx only)

    // in-editor find bar (Ctrl+F), operates on the currently selected tab's text area
    private JPanel findBar;
    private JTextField findField;
    private JCheckBox findMatchCase;
    private JLabel findStatus;

    public CodeView() { this(null); }

    public CodeView(Decompilers decompilers) {
        this.decompilers = decompilers;
        setLayout(new BorderLayout());
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        add(tabs, BorderLayout.CENTER);
        add(buildFindBar(), BorderLayout.SOUTH);

        // Ctrl+F → reveal & focus the find bar (Esc hides it; Enter finds next)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("control F"), "showFind");
        getActionMap().put("showFind", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { showFind(); }
        });
    }

    // ---------- find-in-file ----------

    private JComponent buildFindBar() {
        findBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        findBar.setBackground(Theme.SURFACE);
        findBar.setVisible(false);

        JLabel icon = new JLabel("🔍");
        findField = new JTextField(24);
        findField.setFont(Theme.mono(12));
        findField.addActionListener(e -> findNext(true));
        findField.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "hideFind");
        findField.getActionMap().put("hideFind", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { hideFind(); }
        });

        JButton next = new JButton("▼");
        next.setToolTipText("Next (Enter)");
        next.addActionListener(e -> findNext(true));
        JButton prev = new JButton("▲");
        prev.setToolTipText("Previous");
        prev.addActionListener(e -> findNext(false));

        findMatchCase = new JCheckBox("Aa");
        findMatchCase.setToolTipText("Match case");
        findMatchCase.setBackground(Theme.SURFACE);
        findMatchCase.setForeground(Theme.TEXT);

        JButton close = new JButton("×");
        close.setToolTipText("Close (Esc)");
        close.addActionListener(e -> hideFind());

        findStatus = new JLabel(" ");
        findStatus.setForeground(Theme.MUTED);
        findStatus.setFont(Theme.mono(11));

        findBar.add(icon);
        findBar.add(findField);
        findBar.add(prev);
        findBar.add(next);
        findBar.add(findMatchCase);
        findBar.add(findStatus);
        findBar.add(close);
        return findBar;
    }

    private void showFind() {
        if (currentArea() == null) return;          // nothing open to search
        findBar.setVisible(true);
        revalidate();
        String sel = currentArea().getSelectedText();
        if (sel != null && !sel.isBlank() && !sel.contains("\n")) findField.setText(sel);
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    private void hideFind() {
        findBar.setVisible(false);
        findStatus.setText(" ");
        RSyntaxTextArea area = currentArea();
        if (area != null) { area.clearMarkAllHighlights(); area.requestFocusInWindow(); }
        revalidate();
    }

    private void findNext(boolean forward) {
        RSyntaxTextArea area = currentArea();
        if (area == null || findField.getText().isEmpty()) return;
        SearchContext ctx = new SearchContext(findField.getText());
        ctx.setMatchCase(findMatchCase.isSelected());
        ctx.setSearchForward(forward);
        ctx.setMarkAll(true);
        boolean found = SearchEngine.find(area, ctx).wasFound();
        findStatus.setForeground(found ? Theme.GREEN : Theme.ORANGE);
        findStatus.setText(found ? " " : "no match");
    }

    /** The RSyntaxTextArea inside the currently selected tab, or null. */
    private RSyntaxTextArea currentArea() {
        Component c = tabs.getSelectedComponent();
        if (c instanceof RTextScrollPane sp) return (RSyntaxTextArea) sp.getTextArea();
        if (c instanceof JComponent jc) {
            Object a = jc.getClientProperty("classArea");
            if (a == null) a = jc.getClientProperty("editArea");
            if (a instanceof RSyntaxTextArea r) return r;
        }
        return null;
    }

    /** Open (or focus) a class tab and optionally scroll to a line. */
    public void showClass(JavaClass cls) {
        if (cls == null) return;
        String key = cls.getFullName();

        RSyntaxTextArea existing = openTabs.get(key);
        if (existing != null) {
            tabs.setSelectedComponent(scrollParent(existing));
            return;
        }

        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        area.setEditable(false);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setFont(Theme.mono(13));
        applyDarkTheme(area);

        // jadx decompiles lazily here (the default engine)
        String code;
        try {
            code = cls.getCode();
        } catch (Exception ex) {
            code = "// Failed to decompile " + key + "\n// " + ex;
        }
        area.setText(code);
        area.setCaretPosition(0);

        RTextScrollPane sp = new RTextScrollPane(area);
        sp.setFoldIndicatorEnabled(true);
        sp.putClientProperty("classKey", key);

        // wrap with a decompiler-selector bar (jadx / CFR / Vineflower / Procyon / Best)
        JComponent content = decompilers != null
                ? wrapWithDecompilerBar(cls.getFullName(), area, sp)
                : sp;

        openTabs.put(key, area);
        String title = cls.getName();
        tabs.addTab(title, content);
        int idx = tabs.indexOfComponent(content);
        tabs.setToolTipTextAt(idx, key);
        tabs.setSelectedComponent(content);
        addCloseButton(idx, key, content);
    }

    /** Open a class and scroll to a 1-based line (used by full-text code search). */
    public void showClass(JavaClass cls, int line) {
        showClass(cls);
        if (cls == null || line <= 0) return;
        RSyntaxTextArea area = openTabs.get(cls.getFullName());
        if (area != null) gotoLine(area, line);
    }

    private void gotoLine(RSyntaxTextArea area, int line) {
        try {
            int idx = Math.max(0, Math.min(area.getLineCount() - 1, line - 1));
            int off = area.getLineStartOffset(idx);
            area.setCaretPosition(off);
            try { area.removeAllLineHighlights(); area.addLineHighlight(idx, new Color(0xf0, 0xa5, 0x00, 70)); }
            catch (Exception ignored) {}
            SwingUtilities.invokeLater(() -> {
                try {
                    var r = area.modelToView2D(off);
                    if (r != null) area.scrollRectToVisible(r.getBounds());
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /** Toolbar that re-decompiles the class with a chosen engine (or auto-picks the best). */
    private JComponent wrapWithDecompilerBar(String dottedClass, RSyntaxTextArea area, RTextScrollPane sp) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.putClientProperty("classArea", area);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        JLabel lbl = new JLabel("Decompiler:");
        lbl.setForeground(Theme.MUTED);
        lbl.setFont(Theme.sans(11));
        JComboBox<String> engine = new JComboBox<>(new String[]{"jadx", "CFR", "Vineflower", "Procyon", "Best ★"});
        engine.setFont(Theme.sans(11));
        JLabel status = new JLabel("jadx");
        status.setForeground(Theme.MUTED);
        status.setFont(Theme.mono(11));

        boolean[] mute = {false};   // guard so programmatic combo changes don't re-trigger
        engine.addActionListener(e -> { if (!mute[0]) runEngine((String) engine.getSelectedItem(), dottedClass, area, status); });

        bar.add(lbl);
        bar.add(engine);
        bar.add(status);
        wrapper.add(bar, BorderLayout.NORTH);
        wrapper.add(sp, BorderLayout.CENTER);

        // Auto-fallback: if jadx's output looks incomplete, quietly find a better engine.
        autoFallback(dottedClass, area, status, engine, mute);
        return wrapper;
    }

    /** When jadx scores poorly on this class, run the alternates and switch to the best. */
    private void autoFallback(String dotted, RSyntaxTextArea area, JLabel status, JComboBox<String> engine, boolean[] mute) {
        int jadxScore = Decompilers.quality(area.getText());
        if (jadxScore >= 55) { status.setText("jadx · quality " + jadxScore + "/100"); return; }
        status.setForeground(Theme.ORANGE);
        status.setText("jadx incomplete (" + jadxScore + ") — finding a better decompiler…");
        new SwingWorker<Decompilers.Result, Void>() {
            protected Decompilers.Result doInBackground() {
                var all = decompilers.decompileAll(dotted);
                return all.isEmpty() ? null : all.get(0);
            }
            protected void done() {
                try {
                    Decompilers.Result r = get();
                    if (r != null && r.engine() != Decompilers.Engine.JADX && r.score() > jadxScore) {
                        area.setText(r.source());
                        area.setCaretPosition(0);
                        mute[0] = true; engine.setSelectedItem("Best ★"); mute[0] = false;
                        status.setForeground(Theme.GREEN);
                        status.setText("auto → " + r.engine().label + "  · quality " + r.score()
                                + "/100  (jadx was " + jadxScore + ")");
                    } else {
                        status.setForeground(Theme.ORANGE);
                        status.setText("jadx · quality " + jadxScore + "/100  (no engine did better)");
                    }
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void runEngine(String sel, String dottedClass, RSyntaxTextArea area, JLabel status) {
        status.setForeground(Theme.MUTED);
        status.setText("decompiling…  (first alternate engine builds a one-time cache)");
        final boolean best = sel.startsWith("Best");
        new SwingWorker<Decompilers.Result, Void>() {
            protected Decompilers.Result doInBackground() {
                if (best) {
                    var all = decompilers.decompileAll(dottedClass);
                    return all.isEmpty() ? null : all.get(0);
                }
                Decompilers.Engine eng = switch (sel) {
                    case "CFR" -> Decompilers.Engine.CFR;
                    case "Vineflower" -> Decompilers.Engine.VINEFLOWER;
                    case "Procyon" -> Decompilers.Engine.PROCYON;
                    default -> Decompilers.Engine.JADX;
                };
                return decompilers.decompile(eng, dottedClass);
            }
            protected void done() {
                try {
                    Decompilers.Result r = get();
                    if (r == null) { status.setText("no result"); return; }
                    int caret = 0;
                    area.setText(r.source());
                    area.setCaretPosition(caret);
                    status.setForeground(r.score() >= 60 ? Theme.GREEN : Theme.ORANGE);
                    status.setText((best ? "Best → " + r.engine().label : r.engine().label) + "   · quality " + r.score() + "/100");
                } catch (Exception ex) {
                    status.setForeground(Theme.RED);
                    status.setText("failed: " + ex.getClass().getSimpleName());
                }
            }
        }.execute();
    }

    /** Open a smali file as an EDITABLE tab with a Save bar (writes back to disk). */
    public void openSmaliFile(File file) {
        String key = "smali:" + file.getAbsolutePath();
        RSyntaxTextArea existing = openTabs.get(key);
        if (existing != null) {
            tabs.setSelectedComponent(panelOf(existing));
            return;
        }

        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        area.setEditable(true);
        area.setCodeFoldingEnabled(false);
        area.setFont(Theme.mono(13));
        applyDarkTheme(area);
        try {
            area.setText(Files.readString(file.toPath()));
        } catch (Exception ex) {
            area.setText("; failed to read " + file + "\n; " + ex);
        }
        area.setCaretPosition(0);

        RTextScrollPane sp = new RTextScrollPane(area);

        // editable wrapper with a Save bar (green EDITABLE indicator)
        JPanel wrapper = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        JLabel tag = new JLabel("EDITABLE");
        tag.setForeground(Theme.GREEN);
        tag.setFont(Theme.sans(11));
        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            try {
                Files.writeString(file.toPath(), area.getText());
                tag.setText("Saved ✓");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            }
        });
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void dirty() { tag.setText("EDITABLE *"); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { dirty(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { dirty(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { dirty(); }
        });
        bar.add(tag);
        bar.add(save);
        wrapper.add(bar, BorderLayout.NORTH);
        wrapper.add(sp, BorderLayout.CENTER);
        wrapper.putClientProperty("editArea", area);

        openTabs.put(key, area);
        tabs.addTab(file.getName(), wrapper);
        int idx = tabs.indexOfComponent(wrapper);
        tabs.setToolTipTextAt(idx, file.getAbsolutePath());
        tabs.setSelectedComponent(wrapper);
        addCloseButton(idx, key, wrapper);
    }

    private Component panelOf(RSyntaxTextArea area) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component c = tabs.getComponentAt(i);
            if (c instanceof JComponent jc && jc.getClientProperty("editArea") == area) return c;
            if (c instanceof RTextScrollPane sp && sp.getTextArea() == area) return c;
        }
        return scrollParent(area);
    }

    private Component scrollParent(RSyntaxTextArea area) {
        Component c = area;
        while (c != null && !(c.getParent() instanceof JTabbedPane)) c = c.getParent();
        return c;
    }

    private void addCloseButton(int idx, String key, Component comp) {
        JPanel tab = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tab.setOpaque(false);
        JLabel label = new JLabel(tabs.getTitleAt(idx));
        JButton close = new JButton("×");
        close.setBorder(null);
        close.setContentAreaFilled(false);
        close.setForeground(Theme.MUTED);
        close.setFont(Theme.sans(14));
        close.setMargin(new Insets(0, 4, 0, 0));
        close.addActionListener(e -> {
            int i = tabs.indexOfComponent(comp);
            if (i >= 0) tabs.remove(i);
            openTabs.remove(key);
        });
        tab.add(label);
        tab.add(close);
        tabs.setTabComponentAt(idx, tab);
    }

    private void applyDarkTheme(RSyntaxTextArea area) {
        String themeXml = Theme.dark
                ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                : "/org/fife/ui/rsyntaxtextarea/themes/idea.xml";
        try {
            org.fife.ui.rsyntaxtextarea.Theme t = org.fife.ui.rsyntaxtextarea.Theme.load(
                    getClass().getResourceAsStream(themeXml));
            if (t != null) t.apply(area);
        } catch (Exception ignored) {}
        area.setBackground(Theme.BG);
        area.setForeground(Theme.TEXT);
        area.setCaretColor(Theme.ACCENT);
        area.setCurrentLineHighlightColor(Theme.SURFACE);
        area.setFont(Theme.mono(13));
    }

    /** Re-apply the active (dark/light) syntax theme to every open editor tab. */
    public void reapplyTheme() {
        for (RSyntaxTextArea area : openTabs.values()) {
            applyDarkTheme(area);
            area.repaint();
        }
    }
}
