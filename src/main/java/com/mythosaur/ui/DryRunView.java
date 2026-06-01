package com.mythosaur.ui;

import com.mythosaur.core.DalvikInterpreter;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;
import org.jf.dexlib2.iface.Method;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Dry Run" panel — drives {@link DalvikInterpreter} to actually execute a single
 * method (e.g. an obfuscated string-decryptor) in the sandbox and show its real return
 * value plus a step-by-step register trace. Pure static analysis, no device.
 */
public class DryRunView extends JPanel {

    private final Project project;

    private String targetClass, targetMethod;
    private Method resolved;

    private final JLabel targetLabel = new JLabel("No method selected");
    private final JLabel hintLabel = new JLabel(" ");
    private final JTextField argsField = new JTextField();
    private final JButton runButton = new JButton("Run ▷");
    private final JLabel statusLabel = new JLabel(" ");
    private final JTextArea resultArea = new JTextArea();
    private final DefaultTableModel traceModel =
            new DefaultTableModel(new Object[]{"#", "addr", "instruction", "effect"}, 0) {
                public boolean isCellEditable(int r, int c) { return false; }
            };

    public DryRunView(Project project) {
        this.project = project;
        setLayout(new BorderLayout());
        setBackground(Theme.BG);

        add(buildControls(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);

        runButton.addActionListener(e -> run());
        argsField.addActionListener(e -> run());
        runButton.setEnabled(false);
    }

    private JComponent buildControls() {
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setBackground(Theme.BG);
        north.setBorder(new EmptyBorder(8, 10, 6, 10));

        targetLabel.setFont(Theme.monoBold(13));
        targetLabel.setForeground(Theme.ACCENT);
        targetLabel.setAlignmentX(LEFT_ALIGNMENT);

        hintLabel.setFont(Theme.mono(11));
        hintLabel.setForeground(Theme.MUTED);
        hintLabel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel argsRow = new JPanel(new BorderLayout(8, 0));
        argsRow.setBackground(Theme.BG);
        argsRow.setAlignmentX(LEFT_ALIGNMENT);
        argsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        JLabel al = new JLabel("Args:");
        al.setForeground(Theme.TEXT);
        al.setFont(Theme.sans(12));
        argsField.setFont(Theme.mono(12));
        argsField.setToolTipText("Comma-separated: 5,  \"text\",  100L,  0x1f,  true  (one value per parameter)");
        argsRow.add(al, BorderLayout.WEST);
        argsRow.add(argsField, BorderLayout.CENTER);
        argsRow.add(runButton, BorderLayout.EAST);

        statusLabel.setFont(Theme.mono(11));
        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);

        north.add(targetLabel);
        north.add(Box.createVerticalStrut(2));
        north.add(hintLabel);
        north.add(Box.createVerticalStrut(6));
        north.add(argsRow);
        north.add(Box.createVerticalStrut(4));
        north.add(statusLabel);
        return north;
    }

    private JComponent buildBody() {
        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(false);
        resultArea.setFont(Theme.mono(13));
        resultArea.setBackground(Theme.SURFACE);
        resultArea.setForeground(Theme.GREEN);
        resultArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane resultScroll = new JScrollPane(resultArea);
        resultScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(), "Return value"));

        JTable trace = new JTable(traceModel);
        trace.setFont(Theme.mono(11));
        trace.setRowHeight(18);
        trace.setShowGrid(false);
        trace.setBackground(Theme.BG);
        trace.setForeground(Theme.TEXT);
        trace.getColumnModel().getColumn(0).setMaxWidth(48);
        trace.getColumnModel().getColumn(1).setMaxWidth(70);
        trace.getColumnModel().getColumn(2).setPreferredWidth(220);
        JScrollPane traceScroll = new JScrollPane(trace);
        traceScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(), "Execution trace"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, resultScroll, traceScroll);
        split.setResizeWeight(0.28);
        split.setBorder(null);
        return split;
    }

    /** Wired from method selection: prime the panel with an already-resolved target. */
    public void setTarget(String dottedClass, String methodName, Method resolvedMethod) {
        this.targetClass = dottedClass;
        this.targetMethod = methodName;
        this.resolved = resolvedMethod;
        if (resolved == null || resolved.getImplementation() == null) {
            targetLabel.setText(shortClass(dottedClass) + "." + methodName + "  —  no body to run");
            hintLabel.setText(" ");
            runButton.setEnabled(false);
            return;
        }
        List<? extends CharSequence> ptypes = resolved.getParameterTypes();
        targetLabel.setText(shortClass(dottedClass) + "." + methodName
                + "(" + String.join(", ", ptypes.stream().map(CharSequence::toString).toList()) + ")"
                + " : " + resolved.getReturnType());
        boolean isStatic = org.jf.dexlib2.AccessFlags.STATIC.isSet(resolved.getAccessFlags());
        hintLabel.setText((isStatic ? "static" : "instance — receiver will be null/unknown")
                + (ptypes.isEmpty() ? " · no args" : " · " + ptypes.size() + " param(s)"));
        argsField.setText(defaultArgs(ptypes));
        runButton.setEnabled(true);
        statusLabel.setText("Ready — press Run");
        statusLabel.setForeground(Theme.MUTED);
    }

    private void run() {
        if (resolved == null) return;
        Object[] args;
        try {
            args = parseArgs(argsField.getText());
        } catch (RuntimeException ex) {
            statusLabel.setForeground(Theme.RED);
            statusLabel.setText("Bad args: " + ex.getMessage());
            return;
        }
        runButton.setEnabled(false);
        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setText("Running…");
        resultArea.setText("");
        traceModel.setRowCount(0);

        final Method m = resolved;
        new SwingWorker<DalvikInterpreter.Result, Void>() {
            protected DalvikInterpreter.Result doInBackground() {
                return new DalvikInterpreter(project).run(m, args);
            }
            protected void done() {
                runButton.setEnabled(true);
                try {
                    show(get());
                } catch (Exception ex) {
                    statusLabel.setForeground(Theme.RED);
                    statusLabel.setText("Engine error: " + ex.getClass().getSimpleName());
                }
            }
        }.execute();
    }

    private void show(DalvikInterpreter.Result r) {
        // status
        if (r.timedOut) {
            statusLabel.setForeground(Theme.ORANGE);
            statusLabel.setText("⏱ Step budget exhausted — possible loop, or method too large.");
        } else if (r.unsupported != null) {
            statusLabel.setForeground(Theme.ORANGE);
            statusLabel.setText("⚠ Partially emulated — stopped at: " + r.unsupported);
        } else if (r.returned) {
            statusLabel.setForeground(Theme.GREEN);
            statusLabel.setText("✓ Emulated fully (" + r.trace.size() + " steps)");
        } else {
            statusLabel.setForeground(Theme.MUTED);
            statusLabel.setText("No value returned");
        }

        // value
        String val = "V".equals(r.returnType) ? "(void)" : r.display();
        resultArea.setForeground(r.unsupported != null || r.timedOut ? Theme.ORANGE : Theme.GREEN);
        resultArea.setText(val);
        resultArea.setCaretPosition(0);

        // trace
        for (int i = 0; i < r.trace.size(); i++) {
            DalvikInterpreter.TraceStep s = r.trace.get(i);
            traceModel.addRow(new Object[]{i, "0x" + Integer.toHexString(s.addr), s.insn, s.effect});
        }
    }

    // ---------- argument parsing ----------

    private static String defaultArgs(List<? extends CharSequence> ptypes) {
        List<String> parts = new ArrayList<>();
        for (CharSequence ct : ptypes) {
            String t = ct.toString();
            parts.add(switch (t) {
                case "I", "S", "B", "C" -> "0";
                case "J" -> "0L";
                case "Z" -> "false";
                case "F", "D" -> "0.0";
                case "Ljava/lang/String;" -> "\"\"";
                default -> "null";
            });
        }
        return String.join(", ", parts);
    }

    /** Parse a comma list into typed values: "5", "100L", 0x1f, true, "text", null. */
    static Object[] parseArgs(String text) {
        String t = text.trim();
        if (t.isEmpty()) return new Object[0];
        List<Object> out = new ArrayList<>();
        for (String tokRaw : splitTopLevel(t)) {
            String tok = tokRaw.trim();
            if (tok.isEmpty()) continue;
            if (tok.startsWith("\"") && tok.endsWith("\"") && tok.length() >= 2) {
                out.add(tok.substring(1, tok.length() - 1));
            } else if (tok.equals("null")) {
                out.add(null);
            } else if (tok.equals("true") || tok.equals("false")) {
                out.add(Boolean.parseBoolean(tok) ? 1 : 0);
            } else if (tok.endsWith("L") || tok.endsWith("l")) {
                out.add(Long.parseLong(tok.substring(0, tok.length() - 1).trim()));
            } else if (tok.startsWith("0x") || tok.startsWith("0X")) {
                out.add((int) Long.parseLong(tok.substring(2), 16));
            } else if (tok.contains(".")) {
                out.add(Double.parseDouble(tok));
            } else {
                out.add(Integer.parseInt(tok));
            }
        }
        return out.toArray();
    }

    /** Split on commas that aren't inside quotes. */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        boolean inStr = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inStr = !inStr;
            else if (c == ',' && !inStr) { parts.add(s.substring(start, i)); start = i + 1; }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static String shortClass(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot >= 0 ? dotted.substring(dot + 1) : dotted;
    }
}
