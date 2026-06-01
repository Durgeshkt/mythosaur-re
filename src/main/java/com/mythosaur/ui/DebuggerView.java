package com.mythosaur.ui;

import com.mythosaur.core.AdbBridge;
import com.mythosaur.core.CfgBuilder;
import com.mythosaur.core.JdwpDebugger;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Phase B UI — the smali-level live debugger. Pick a connected device + a debuggable
 * process, attach (JDWP via {@link JdwpDebugger}), arm breakpoints on smali instruction
 * offsets, then step and inspect frames/registers while the app runs on the device.
 *
 * <p>This is the one place the tool talks to a device; it is fully optional and explains
 * itself when adb/device is missing. It is standard debugging (the ART JDWP channel),
 * not instrumentation.
 */
public class DebuggerView extends JPanel implements JdwpDebugger.Listener {

    private final Project project;
    private final Supplier<File> apkSupplier;     // which APK to install (patched if available)
    private final AdbBridge adb = new AdbBridge();
    private final JdwpDebugger dbg = new JdwpDebugger(this);

    private String targetClass, targetMethod, targetSig;
    private List<CfgBuilder.Insn> insns = new ArrayList<>();
    private final Set<JdwpDebugger.Bp> breakpoints = new HashSet<>();  // ALL breakpoints, across every method
    private long pausedIndex = -1;                       // highlighted line when paused in the displayed method

    private final JComboBox<AdbBridge.Device> deviceCombo = new JComboBox<>();
    private final JComboBox<AdbBridge.Proc> procCombo = new JComboBox<>();
    private final JButton attachBtn = new JButton("Attach");
    private final JButton installBtn = new JButton("Install + Launch");
    private final JButton resumeBtn = new JButton("Resume");
    private final JButton intoBtn = new JButton("Step Into");
    private final JButton overBtn = new JButton("Step Over");
    private final JButton outBtn = new JButton("Step Out");
    private final JLabel adbStatus = new JLabel();

    private final SmaliModel smaliModel = new SmaliModel();
    private final JTable smaliTable = new JTable(smaliModel);
    private final DefaultListModel<JdwpDebugger.FrameInfo> framesModel = new DefaultListModel<>();
    private final JList<JdwpDebugger.FrameInfo> framesList = new JList<>(framesModel);
    private final DefaultTableModel localsModel =
            new DefaultTableModel(new Object[]{"name", "type", "value"}, 0) {
                public boolean isCellEditable(int r, int c) { return false; }
            };
    private final JTextArea log = new JTextArea();

    public DebuggerView(Project project, Supplier<File> apkSupplier) {
        this.project = project;
        this.apkSupplier = apkSupplier;
        setLayout(new BorderLayout());
        setBackground(Theme.BG);
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        wire();
        refreshAdb();
        updateButtons();
    }

    // ---------- layout ----------

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBackground(Theme.SURFACE);
        adbStatus.setFont(Theme.mono(11));
        JButton refresh = new JButton("⟳");
        refresh.setToolTipText("Refresh devices & processes");
        refresh.addActionListener(e -> refreshAdb());
        bar.add(adbStatus);
        bar.add(new JLabel("  Device:"));
        deviceCombo.setPreferredSize(new Dimension(180, 26));
        bar.add(deviceCombo);
        bar.add(new JLabel("Process:"));
        procCombo.setPreferredSize(new Dimension(220, 26));
        bar.add(procCombo);
        bar.add(refresh);
        bar.add(attachBtn);
        bar.add(installBtn);
        bar.add(new JLabel("  |  "));
        bar.add(resumeBtn);
        bar.add(intoBtn);
        bar.add(overBtn);
        bar.add(outBtn);
        return bar;
    }

    private JComponent buildBody() {
        // left: smali listing with breakpoint gutter
        smaliTable.setFont(Theme.mono(12));
        smaliTable.setRowHeight(18);
        smaliTable.setShowGrid(false);
        smaliTable.setBackground(Theme.BG);
        smaliTable.setForeground(Theme.TEXT);
        smaliTable.setDefaultRenderer(Object.class, new SmaliRenderer());
        smaliTable.getColumnModel().getColumn(0).setMaxWidth(28);   // bp gutter
        smaliTable.getColumnModel().getColumn(1).setMaxWidth(72);   // addr
        JScrollPane smaliScroll = new JScrollPane(smaliTable);
        smaliScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(), "Smali — click the left gutter to toggle a breakpoint"));

        // right: frames + locals
        framesList.setFont(Theme.mono(11));
        framesList.setBackground(Theme.BG);
        framesList.setForeground(Theme.TEXT);
        JScrollPane framesScroll = new JScrollPane(framesList);
        framesScroll.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(), "Call stack"));

        JTable localsTable = new JTable(localsModel);
        localsTable.setFont(Theme.mono(11));
        localsTable.setBackground(Theme.BG);
        localsTable.setForeground(Theme.TEXT);
        localsTable.setRowHeight(18);
        JScrollPane localsScroll = new JScrollPane(localsTable);
        localsScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(), "Registers / locals (this + variables)"));

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, framesScroll, localsScroll);
        rightSplit.setResizeWeight(0.4);
        rightSplit.setBorder(null);

        JSplitPane mid = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, smaliScroll, rightSplit);
        mid.setResizeWeight(0.58);
        mid.setBorder(null);

        log.setEditable(false);
        log.setFont(Theme.mono(11));
        log.setBackground(Theme.SURFACE);
        log.setForeground(Theme.MUTED);
        log.setRows(4);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(), "Debugger log"));

        JSplitPane outer = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mid, logScroll);
        outer.setResizeWeight(0.78);
        outer.setBorder(null);
        return outer;
    }

    private void wire() {
        attachBtn.addActionListener(e -> { if (dbg.isAttached()) doDetach(); else doAttach(); });
        installBtn.addActionListener(e -> doInstallLaunch());
        resumeBtn.addActionListener(e -> dbg.resume());
        intoBtn.addActionListener(e -> dbg.stepInto());
        overBtn.addActionListener(e -> dbg.stepOver());
        outBtn.addActionListener(e -> dbg.stepOut());

        // click the bp gutter (col 0) to toggle a breakpoint on that instruction
        smaliTable.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = smaliTable.rowAtPoint(e.getPoint());
                int col = smaliTable.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 0) toggleBreakpoint(row);
            }
        });

        // click a call-stack frame to open that method's smali at its current instruction
        framesList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                JdwpDebugger.FrameInfo f = framesList.getSelectedValue();
                if (f != null) navigateToFrame(f);
            }
        });
    }

    // ---------- target priming (from method selection) ----------

    public void setTarget(String dottedClass, String methodName, Method resolved) {
        showMethod(dottedClass, methodName, resolved, -1);
    }

    /** Display a method's smali; breakpoints persist (they're keyed by class+method+sig+offset). */
    private void showMethod(String dottedClass, String methodName, Method resolved, long highlight) {
        this.targetClass = dottedClass;
        this.targetMethod = methodName;
        this.pausedIndex = highlight;
        if (resolved == null || resolved.getImplementation() == null) {
            this.insns = new ArrayList<>();
            this.targetSig = null;
            smaliModel.fireTableDataChanged();
            return;
        }
        this.targetSig = signatureOf(resolved);
        this.insns = CfgBuilder.linear(resolved);
        smaliModel.fireTableDataChanged();
        if (highlight >= 0) {
            int row = rowForAddr(highlight);
            if (row >= 0) smaliTable.scrollRectToVisible(smaliTable.getCellRect(row, 0, true));
        }
    }

    /** Open the smali of the method in a stack frame and highlight its current instruction. */
    private void navigateToFrame(JdwpDebugger.FrameInfo f) {
        if (f == null) return;
        if (f.className().equals(targetClass) && f.methodName().equals(targetMethod)
                && (targetSig == null || targetSig.equals(f.methodSig()))) {
            // already showing this method — just move the highlight (no class scan needed)
            pausedIndex = f.codeIndex();
            int row = rowForAddr(pausedIndex);
            if (row >= 0) smaliTable.scrollRectToVisible(smaliTable.getCellRect(row, 0, true));
            smaliTable.repaint();
            return;
        }
        // resolving the method scans every class (40k+) — do it off the EDT
        new SwingWorker<Method, Void>() {
            protected Method doInBackground() { return findMethodBySig(f.className(), f.methodName(), f.methodSig()); }
            protected void done() {
                try {
                    Method m = get();
                    if (m == null) { log("no smali for " + f.label() + " (framework/synthetic method)"); return; }
                    showMethod(f.className(), f.methodName(), m, f.codeIndex());
                    smaliTable.repaint();
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    /** Resolve a dexlib2 method by class + name + exact JVM signature (handles overloads). */
    private Method findMethodBySig(String dottedClass, String name, String sig) {
        String desc = "L" + dottedClass.replace('.', '/') + ";";
        for (DexBackedDexFile dex : project.getDexFiles()) {
            for (ClassDef cls : dex.getClasses()) {
                if (!cls.getType().equals(desc)) continue;
                for (Method m : cls.getMethods()) {
                    if (m.getName().equals(name) && m.getImplementation() != null
                            && (sig == null || sig.isEmpty() || signatureOf(m).equals(sig))) {
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private static String signatureOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (CharSequence p : m.getParameterTypes()) sb.append(p);
        return sb.append(')').append(m.getReturnType()).toString();
    }

    // ---------- adb actions ----------

    private void refreshAdb() {
        if (!adb.available()) {
            adbStatus.setText("adb: NOT FOUND");
            adbStatus.setForeground(Theme.RED);
            attachBtn.setEnabled(false);
            installBtn.setEnabled(false);
            log("adb not found. Install it (e.g. `sudo apt install android-tools-adb`) or set ANDROID_HOME, "
                    + "then connect a device/emulator with USB debugging. This panel is the only device-dependent feature.");
            return;
        }
        adbStatus.setText("adb: " + adb.adbPath());
        adbStatus.setForeground(Theme.GREEN);
        new SwingWorker<Object[], Void>() {
            List<AdbBridge.Device> devs;
            List<AdbBridge.Proc> procs;
            protected Object[] doInBackground() {
                devs = adb.devices();
                if (!devs.isEmpty()) adb.setSerial(devs.get(0).serial());
                procs = adb.debuggableProcesses();
                return null;
            }
            protected void done() {
                deviceCombo.removeAllItems();
                for (AdbBridge.Device d : devs) deviceCombo.addItem(d);
                procCombo.removeAllItems();
                for (AdbBridge.Proc p : procs) procCombo.addItem(p);
                if (devs.isEmpty()) log("No device/emulator detected (`adb devices` empty).");
                else log(devs.size() + " device(s), " + procs.size() + " debuggable process(es).");
                updateButtons();
            }
        }.execute();
    }

    private void doAttach() {
        AdbBridge.Proc proc = (AdbBridge.Proc) procCombo.getSelectedItem();
        AdbBridge.Device dev = (AdbBridge.Device) deviceCombo.getSelectedItem();
        if (proc == null) { log("Select a debuggable process to attach to."); return; }
        if (dev != null) adb.setSerial(dev.serial());
        attachBtn.setEnabled(false);
        new SwingWorker<Integer, Void>() {
            Exception err;
            protected Integer doInBackground() {
                try {
                    int port = adb.forwardJdwp(proc.pid());
                    dbg.attach("127.0.0.1", port);
                    return port;
                } catch (Exception ex) { err = ex; return -1; }
            }
            protected void done() {
                if (err != null) { log("Attach failed: " + err.getMessage()); attachBtn.setEnabled(true); }
                updateButtons();
            }
        }.execute();
    }

    private void doDetach() { dbg.detach(); }

    private void doInstallLaunch() {
        File apk = apkSupplier.get();
        if (apk == null || !apk.isFile()) { log("No APK to install."); return; }
        String pkg = project.getManifest().getPackageName();
        String launcher = project.getManifest().getLauncherActivity();
        if (pkg == null) { log("Could not read package name from manifest."); return; }
        installBtn.setEnabled(false);
        new SwingWorker<String, Void>() {
            protected String doInBackground() {
                StringBuilder sb = new StringBuilder();
                sb.append("install: ").append(adb.install(apk)).append('\n');
                if (launcher != null) {
                    String comp = pkg + "/" + (launcher.startsWith(".") ? pkg + launcher : launcher);
                    sb.append("launch: ").append(adb.launch(comp));
                } else sb.append("(no launcher activity in manifest — launch the app manually)");
                return sb.toString();
            }
            protected void done() {
                try { log(get()); } catch (Exception ex) { log("install/launch error: " + ex.getMessage()); }
                installBtn.setEnabled(true);
                refreshAdb();
            }
        }.execute();
    }

    // ---------- breakpoints ----------

    private void toggleBreakpoint(int row) {
        if (row < 0 || row >= insns.size() || targetClass == null) return;
        long idx = insns.get(row).addr;
        JdwpDebugger.Bp bp = new JdwpDebugger.Bp(targetClass, targetMethod, targetSig, idx);
        if (breakpoints.contains(bp)) {
            breakpoints.remove(bp);
            dbg.removeBreakpoint(bp);
            log("breakpoint cleared " + shortClass(targetClass) + "." + targetMethod + " @0x" + Long.toHexString(idx));
        } else {
            breakpoints.add(bp);
            dbg.addBreakpoint(bp);
            log("breakpoint armed " + shortClass(targetClass) + "." + targetMethod + " @0x" + Long.toHexString(idx)
                    + (dbg.isAttached() ? "" : " (activates on attach / class load)"));
        }
        smaliModel.fireTableRowsUpdated(row, row);
    }

    private static String shortClass(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot >= 0 ? dotted.substring(dot + 1) : dotted;
    }

    // ---------- JdwpDebugger.Listener (events arrive off-EDT) ----------

    public void onAttached(String vmName) {
        SwingUtilities.invokeLater(() -> { log("attached to " + vmName); updateButtons(); });
    }
    public void onResumed() {
        SwingUtilities.invokeLater(() -> { pausedIndex = -1; smaliTable.repaint(); updateButtons(); });
    }
    public void onDetached(String reason) {
        SwingUtilities.invokeLater(() -> {
            log("detached: " + reason);
            pausedIndex = -1; framesModel.clear(); localsModel.setRowCount(0);
            smaliTable.repaint(); updateButtons();
        });
    }
    public void onLog(String message) { SwingUtilities.invokeLater(() -> log(message)); }

    public void onPaused(JdwpDebugger.PauseInfo info) {
        SwingUtilities.invokeLater(() -> {
            framesModel.clear();
            for (JdwpDebugger.FrameInfo f : info.frames) framesModel.addElement(f);
            localsModel.setRowCount(0);
            if (!info.localsAvailable) localsModel.addRow(new Object[]{
                    "(note)", "", "register names stripped (release build) — showing this + args"});
            for (JdwpDebugger.VarValue v : info.locals) localsModel.addRow(new Object[]{v.name(), v.type(), v.value()});

            // auto-open the top frame's method at the paused instruction (selecting it
            // fires the frames-list listener → navigateToFrame)
            if (info.top != null) {
                log("paused in " + info.top.label());
                if (!framesModel.isEmpty()) framesList.setSelectedIndex(0);
                else navigateToFrame(info.top);
            }
            updateButtons();
        });
    }

    private int rowForAddr(long addr) {
        for (int i = 0; i < insns.size(); i++) if (insns.get(i).addr == addr) return i;
        return -1;
    }

    // ---------- ui state ----------

    private void updateButtons() {
        boolean attached = dbg.isAttached();
        boolean paused = dbg.isPaused();
        attachBtn.setText(attached ? "Detach" : "Attach");
        attachBtn.setEnabled(adb.available());
        resumeBtn.setEnabled(attached && paused);
        intoBtn.setEnabled(attached && paused);
        overBtn.setEnabled(attached && paused);
        outBtn.setEnabled(attached && paused);
    }

    private void log(String s) {
        log.append(s + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    // ---------- smali table ----------

    private final class SmaliModel extends AbstractTableModel {
        public int getRowCount() { return insns.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int c) { return c == 0 ? "" : c == 1 ? "addr" : "instruction"; }
        public Object getValueAt(int r, int c) {
            CfgBuilder.Insn in = insns.get(r);
            return switch (c) {
                case 0 -> breakpoints.contains(
                        new JdwpDebugger.Bp(targetClass, targetMethod, targetSig, in.addr)) ? "●" : "";
                case 1 -> "0x" + Integer.toHexString(in.addr);
                default -> in.text;
            };
        }
    }

    private final class SmaliRenderer extends javax.swing.table.DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            boolean isPaused = row >= 0 && row < insns.size() && insns.get(row).addr == pausedIndex;
            if (isPaused) { c.setBackground(Theme.ACCENT); c.setForeground(Color.BLACK); }
            else if (!sel) { c.setBackground(Theme.BG); c.setForeground(col == 1 ? Theme.MUTED : Theme.TEXT); }
            if (col == 0) { c.setForeground(Theme.RED); setHorizontalAlignment(CENTER); }
            else setHorizontalAlignment(LEFT);
            return c;
        }
    }
}
