package com.mythosaur.ui;

import com.mythosaur.core.ApkLoader;
import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;

/**
 * Main IDE window. Layout:
 *   ┌───────────────────────────────────────────┐
 *   │ menu bar                                   │
 *   ├──────────┬────────────────────┬────────────┤
 *   │ project  │  code view (tabs)  │  analysis  │
 *   │  tree    │                    │  / xrefs   │
 *   ├──────────┴────────────────────┴────────────┤
 *   │ status bar                                  │
 *   └───────────────────────────────────────────┘
 */
public class MainWindow extends JFrame {

    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel countsLabel = new JLabel("");
    private JPanel centerPlaceholder;
    private Project project;
    private CodeView codeView;
    private JTabbedPane leftTabs;
    private File lastBuiltApk;
    private JMenu recentMenu;

    public MainWindow() {
        setTitle("Mythosaur RE");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1400, 900);
        setMinimumSize(new Dimension(1000, 600));
        setLocationRelativeTo(null);
        loadAppIcons();

        setJMenuBar(buildMenuBar());
        setLayout(new BorderLayout());

        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open APK…");
        open.setAccelerator(KeyStroke.getKeyStroke("control O"));
        open.addActionListener(e -> chooseApk());
        JMenuItem openWs = new JMenuItem("Open Workspace…");
        openWs.addActionListener(e -> chooseWorkspace());
        recentMenu = new JMenu("Recent Projects");
        JMenuItem mapping = new JMenuItem("Load ProGuard Mapping…");
        mapping.addActionListener(e -> loadMapping());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        file.add(open);
        file.add(openWs);
        file.add(recentMenu);
        file.addSeparator();
        file.add(mapping);
        file.addSeparator();
        file.add(exit);

        // keep the Recent submenu fresh every time File is opened
        file.addMenuListener(new javax.swing.event.MenuListener() {
            public void menuSelected(javax.swing.event.MenuEvent e) { rebuildRecentMenu(); }
            public void menuDeselected(javax.swing.event.MenuEvent e) {}
            public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });

        JMenu build = new JMenu("Build");
        JMenuItem disasm = new JMenuItem("Disassemble to Smali");
        disasm.addActionListener(e -> disassembleToSmali());
        JMenuItem rebuild = new JMenuItem("Rebuild Signed APK (smali lib)");
        rebuild.setAccelerator(KeyStroke.getKeyStroke("control B"));
        rebuild.addActionListener(e -> rebuildApk());
        JMenuItem fullDecode = new JMenuItem("Full Decode (apktool — res + manifest)");
        fullDecode.addActionListener(e -> apktoolDecode());
        JMenuItem apktoolBuild = new JMenuItem("Rebuild with apktool + sign + verify");
        apktoolBuild.setAccelerator(KeyStroke.getKeyStroke("control shift B"));
        apktoolBuild.addActionListener(e -> apktoolRebuild());
        JMenuItem install = new JMenuItem("Install Patched APK (adb)");
        install.addActionListener(e -> installApk());
        build.add(disasm);
        build.add(rebuild);
        build.addSeparator();
        build.add(fullDecode);
        build.add(apktoolBuild);
        build.addSeparator();
        build.add(install);

        JMenu view = new JMenu("View");
        ButtonGroup themeGroup = new ButtonGroup();
        JRadioButtonMenuItem darkItem = new JRadioButtonMenuItem("Dark", true);
        JRadioButtonMenuItem lightItem = new JRadioButtonMenuItem("Light", false);
        darkItem.addActionListener(e -> switchTheme(true));
        lightItem.addActionListener(e -> switchTheme(false));
        themeGroup.add(darkItem);
        themeGroup.add(lightItem);
        view.add(darkItem);
        view.add(lightItem);

        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About Mythosaur");
        about.addActionListener(e -> showAbout());
        help.add(about);

        bar.add(file);
        bar.add(build);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    private JComponent buildCenter() {
        centerPlaceholder = new JPanel(new GridBagLayout());
        centerPlaceholder.setBackground(Theme.BG);

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);

        JLabel logo = new JLabel("◈");
        logo.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
        logo.setForeground(Theme.ACCENT);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel("MYTHOSAUR RE");
        title.setFont(Theme.sans(20));
        title.setForeground(Theme.ACCENT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel sub = new JLabel("Open an APK to begin  (Ctrl+O)");
        sub.setFont(Theme.sans(13));
        sub.setForeground(Theme.MUTED);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        sub.setBorder(new EmptyBorder(12, 0, 0, 0));

        inner.add(logo);
        inner.add(Box.createVerticalStrut(12));
        inner.add(title);
        inner.add(sub);

        centerPlaceholder.add(inner);
        return centerPlaceholder;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.SURFACE);
        bar.setBorder(new EmptyBorder(3, 10, 3, 10));

        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setFont(Theme.sans(11));

        countsLabel.setForeground(Theme.ACCENT);
        countsLabel.setFont(Theme.mono(11));

        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(countsLabel, BorderLayout.EAST);
        return bar;
    }

    private void chooseApk() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open APK");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Android Package (*.apk)", "apk"));
        File initial = new File(System.getProperty("user.home") + "/.mythosaur/workspaces");
        if (initial.isDirectory()) chooser.setCurrentDirectory(initial);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openApk(chooser.getSelectedFile());
        }
    }

    /** Rebuild the Recent Projects submenu from the on-disk recents list. */
    private void rebuildRecentMenu() {
        recentMenu.removeAll();
        java.util.List<com.mythosaur.core.ProjectStore.Entry> recent = com.mythosaur.core.ProjectStore.recent();
        if (recent.isEmpty()) {
            JMenuItem none = new JMenuItem("(no recent projects)");
            none.setEnabled(false);
            recentMenu.add(none);
            return;
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (var e : recent) {
            String when = e.opened() > 0 ? fmt.format(new java.util.Date(e.opened())) : "";
            JMenuItem item = new JMenuItem(e.name() + (when.isEmpty() ? "" : "    " + when));
            item.setToolTipText(e.apk().getAbsolutePath() + (e.apkExists() ? "" : "  (APK moved/missing)"));
            if (!e.apkExists()) item.setForeground(Theme.MUTED);
            item.addActionListener(ev -> reopen(e));
            recentMenu.add(item);
        }
    }

    /** Pick a workspace folder (~/.mythosaur/workspaces/<...>) and reopen its project. */
    private void chooseWorkspace() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Mythosaur Workspace");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File wsRoot = new File(System.getProperty("user.home") + "/.mythosaur/workspaces");
        if (wsRoot.isDirectory()) chooser.setCurrentDirectory(wsRoot);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var entry = com.mythosaur.core.ProjectStore.read(chooser.getSelectedFile().toPath());
        if (entry == null) {
            JOptionPane.showMessageDialog(this,
                    "That folder is not a Mythosaur workspace (no mythosaur.project descriptor).",
                    "Open Workspace", JOptionPane.WARNING_MESSAGE);
            return;
        }
        reopen(entry);
    }

    /** Reopen a stored project, relocating the APK if its original path is gone. */
    private void reopen(com.mythosaur.core.ProjectStore.Entry entry) {
        File apk = entry.apk();
        if (!entry.apkExists()) {
            int c = JOptionPane.showConfirmDialog(this,
                    "Original APK not found:\n" + apk.getAbsolutePath()
                            + "\n\nLocate it now?", "APK Moved", JOptionPane.YES_NO_OPTION);
            if (c != JOptionPane.YES_OPTION) return;
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Locate " + entry.name() + ".apk");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Android Package (*.apk)", "apk"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            apk = chooser.getSelectedFile();
        }
        setStatus("Reopening " + entry.name() + "…");
        openApk(apk, entry.mapping() != null && entry.mapping().isFile() ? entry.mapping() : null);
    }

    private void loadMapping() {
        if (project == null) { needProject(); return; }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load ProGuard/R8 mapping.txt");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Mapping files (*.txt, *.map)", "txt", "map"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File mapping = chooser.getSelectedFile();
        File apk = project.getApkFile();
        openApk(apk, mapping);
    }

    /** Loads an APK on a background thread via jadx + dexlib2 libraries. */
    public void openApk(File apk) { openApk(apk, null); }

    public void openApk(File apk, File mappingFile) {
        final JDialog progressDialog = buildProgressDialog();
        final JLabel progressMsg = (JLabel) progressDialog.getContentPane().getComponent(1);

        SwingWorker<Project, String> worker = new SwingWorker<>() {
            @Override
            protected Project doInBackground() throws Exception {
                return ApkLoader.load(apk, mappingFile, this::publish);
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                if (!chunks.isEmpty()) progressMsg.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    Project loaded = get();
                    // release the previous project (jadx holds the whole class graph) before swapping
                    final Project previous = project;
                    project = loaded;
                    if (previous != null) {
                        new Thread(previous::close, "project-close").start();
                    }
                    onProjectLoaded(project);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String m = describe(cause);
                    JOptionPane.showMessageDialog(MainWindow.this,
                            "Failed to load APK:\n" + m,
                            "Load Error", JOptionPane.ERROR_MESSAGE);
                    setStatus("Load failed: " + m);
                }
            }
        };
        worker.execute();
        progressDialog.setVisible(true); // modal, blocks until disposed in done()
    }

    private JDialog buildProgressDialog() {
        JDialog dialog = new JDialog(this, "Loading APK", true);
        dialog.setSize(420, 130);
        dialog.setLocationRelativeTo(this);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(20, 24, 20, 24));
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel msg = new JLabel("Starting…");
        msg.setForeground(Theme.MUTED);
        msg.setBorder(new EmptyBorder(12, 0, 0, 0));
        msg.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(bar);
        panel.add(msg);
        dialog.setContentPane(panel);
        return dialog;
    }

    /** Called on EDT once the Project is loaded. Builds the real workspace UI. */
    private void onProjectLoaded(Project p) {
        setTitle("Mythosaur RE — " + p.getName());
        var an = p.getAnalyzer();
        setCounts(an.getTotalClasses() + " classes · "
                + an.getTotalMethods() + " methods · "
                + an.getTotalStrings() + " strings");
        setStatus("Loaded " + p.getName());

        // --- center: code view (with the multi-decompiler hub) ---
        codeView = new CodeView(new com.mythosaur.core.Decompilers(p));

        // navigation: open a class by full name
        java.util.function.Consumer<String> openByName = fullName -> {
            var cls = p.findClass(fullName);
            if (cls != null) codeView.showClass(cls);
        };
        java.util.function.BiConsumer<String, Integer> openAtLine = (fullName, line) -> {
            var cls = p.findClass(fullName);
            if (cls != null) codeView.showClass(cls, line);
        };

        // --- bottom: xref + graph ---
        XrefPanel xrefPanel = new XrefPanel(p, openByName);
        GraphView graphView = new GraphView(p, openByName);

        java.util.function.BiConsumer<String, String> onMethod = (cls, method) -> {
            xrefPanel.showFor(cls, method);
            graphView.showFor(cls, method);
        };

        // App Navigation Flow — the headline feature, built on a worker thread
        AppFlowView appFlowView = new AppFlowView(p, openByName);

        // Method CFG — Cutter-style control flow graph
        CfgView cfgView = new CfgView();

        // Protections — packer / obfuscation / anti-RE / encrypted payloads
        ProtectionsPanel protectionsPanel = new ProtectionsPanel(p);

        // Permissions abuse map — dangerous/abused permissions linked to their code usage
        PermissionsView permissionsView = new PermissionsView(p, openByName);

        // Dry Run — sandboxed Dalvik emulator (run a decryptor, see the real output)
        DryRunView dryRunView = new DryRunView(p);

        // Debugger — live JDWP smali debugger (optional, needs adb + a debuggable app on a device)
        DebuggerView debuggerView = new DebuggerView(p,
                () -> (lastBuiltApk != null && lastBuiltApk.isFile()) ? lastBuiltApk : p.getApkFile());

        JTabbedPane bottomTabs = new JTabbedPane();
        bottomTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        bottomTabs.addTab("App Flow", appFlowView);
        bottomTabs.addTab("Protections", protectionsPanel);
        bottomTabs.addTab("Permissions", permissionsView);
        bottomTabs.addTab("Method CFG", cfgView);
        bottomTabs.addTab("Dry Run", dryRunView);
        bottomTabs.addTab("Debugger", debuggerView);
        bottomTabs.addTab("Cross-References", xrefPanel);
        bottomTabs.addTab("Call Graph", graphView);
        SearchView searchView = new SearchView(p, openByName, openAtLine);
        bottomTabs.addTab("Search", searchView);

        // Ctrl+Shift+F → jump to global search
        JRootPane rp = getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke("control shift F"), "focusSearch");
        rp.getActionMap().put("focusSearch", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int idx = bottomTabs.indexOfTab("Search");
                if (idx >= 0) { bottomTabs.setSelectedIndex(idx); searchView.focusQuery(); }
            }
        });

        // auto-run protection analysis — run() does its own background work on a SwingWorker,
        // so it must be invoked on the EDT (it updates the panel before/after).
        protectionsPanel.run();
        permissionsView.run();   // permission abuse map (background)

        // when a method is selected, also build its control-flow graph
        java.util.function.BiConsumer<String, String> baseOnMethod = onMethod;
        onMethod = (cls, method) -> {
            baseOnMethod.accept(cls, method);
            // findMethod scans every class (40k+ on big apps) and CfgBuilder walks the
            // instruction stream — resolve once off the EDT, then feed CFG + Dry Run.
            new SwingWorker<org.jf.dexlib2.iface.Method, Void>() {
                protected org.jf.dexlib2.iface.Method doInBackground() {
                    return p.findMethod(cls, method);
                }
                protected void done() {
                    try {
                        var dexMethod = get();
                        if (dexMethod != null) cfgView.show(com.mythosaur.core.CfgBuilder.build(dexMethod));
                        dryRunView.setTarget(cls, method, dexMethod); // prime the emulator
                        debuggerView.setTarget(cls, method, dexMethod); // prime the debugger's smali view
                    } catch (Exception ignored) { /* method removed or unparseable — leave views as-is */ }
                }
            }.execute();
        };

        new SwingWorker<Void, Void>() {
            protected Void doInBackground() {
                appFlowView.build(); // scans manifest + instructions
                return null;
            }
            protected void done() {
                int count = p.getManifest().getActivities().size();
                int edges = p.getFlowAnalyzer().getEdges().size();
                int tab = bottomTabs.indexOfTab("App Flow");
                if (tab >= 0) bottomTabs.setTitleAt(tab, "App Flow (" + count + " activities)");
                setStatus("App flow: " + count + " activities, " + edges + " navigation edges");
            }
        }.execute();

        // --- left: project tree + analysis ---
        ProjectTree projectTree = new ProjectTree(p, codeView::showClass);
        AnalysisPanel analysisPanel = new AnalysisPanel(p, openByName, onMethod);

        OverviewPanel overviewPanel = new OverviewPanel(p);

        leftTabs = new JTabbedPane();
        leftTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        leftTabs.addTab("Overview", overviewPanel);
        leftTabs.addTab("Project", projectTree);
        leftTabs.addTab("Analysis", analysisPanel);
        leftTabs.addTab("Manifest", new ManifestView(p));
        leftTabs.addTab("Resources", new ResourcesTree(p));
        if (!p.getNativeAnalyzer().listSoEntries().isEmpty()) {
            leftTabs.addTab("Native", new NativeView(p));
        }
        overviewPanel.run();   // gather APK info + signature in the background

        // Restore existing patch output from the workspace (so a reopened project keeps
        // its smali/apktool edits without re-running Disassemble / Full Decode).
        var patcher = p.getSmaliPatcher();
        if (hasFiles(patcher.getSmaliRoot())) {
            leftTabs.addTab("Smali", new SmaliTree(patcher.getSmaliRoot().toFile(),
                    f -> codeView.openSmaliFile(f)));
        }
        if (hasFiles(patcher.getApktoolRoot())) {
            leftTabs.addTab("apktool", new SmaliTree(patcher.getApktoolRoot().toFile(),
                    f -> codeView.openSmaliFile(f)));
        }

        // --- assemble splits ---
        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeView, bottomTabs);
        centerSplit.setResizeWeight(0.7);
        centerSplit.setBorder(null);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTabs, centerSplit);
        mainSplit.setResizeWeight(0.22);
        mainSplit.setBorder(null);

        // Replace whatever is currently in CENTER (placeholder on first load, old split on reload)
        BorderLayout bl = (BorderLayout) getContentPane().getLayout();
        Component existing = bl.getLayoutComponent(BorderLayout.CENTER);
        if (existing != null) getContentPane().remove(existing);
        getContentPane().add(mainSplit, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    // ---------- Smali patch pipeline ----------

    private void disassembleToSmali() {
        if (project == null) { needProject(); return; }
        runPipeline("Disassembling to smali…", p -> {
            project.getSmaliPatcher().disassemble(p::publishMsg);
            return null;
        }, ok -> {
            // add a Smali tab with the file tree
            var patcher = project.getSmaliPatcher();
            SmaliTree tree = new SmaliTree(patcher.getSmaliRoot().toFile(),
                    f -> codeView.openSmaliFile(f));
            if (leftTabs.indexOfTab("Smali") < 0) {
                leftTabs.addTab("Smali", tree);
            }
            leftTabs.setSelectedIndex(leftTabs.indexOfTab("Smali"));
            setStatus("Disassembled to smali — edit files, then Build → Rebuild Signed APK");
        });
    }

    private void apktoolDecode() {
        if (project == null) { needProject(); return; }
        if (!com.mythosaur.core.ApktoolService.available()) {
            JOptionPane.showMessageDialog(this, "apktool not installed (sudo apt install apktool)");
            return;
        }
        runPipeline("Full decode (apktool)…", p -> project.getSmaliPatcher().apktoolDecode(p::publishMsg),
            ok -> {
                java.nio.file.Path root = project.getSmaliPatcher().getApktoolRoot();
                SmaliTree tree = new SmaliTree(root.toFile(), f -> codeView.openSmaliFile(f));
                if (leftTabs.indexOfTab("apktool") < 0) leftTabs.addTab("apktool", tree);
                leftTabs.setSelectedIndex(leftTabs.indexOfTab("apktool"));
                setStatus("Full decode done — edit smali / res / AndroidManifest, then Build → Rebuild with apktool");
            });
    }

    private void apktoolRebuild() {
        if (project == null) { needProject(); return; }
        runPipeline("Rebuild with apktool + sign…", p -> {
            lastBuiltApk = project.getSmaliPatcher().apktoolRebuild(p::publishMsg);
            return lastBuiltApk;
        }, ok -> {
            String verify = project.getSmaliPatcher().verify(lastBuiltApk);
            int choice = JOptionPane.showConfirmDialog(this,
                    "Rebuilt + signed via apktool:\n" + lastBuiltApk.getAbsolutePath()
                            + "\n\nSignature: " + verify + "\n\nInstall via adb?",
                    "apktool Rebuild Complete", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            setStatus("apktool build: " + lastBuiltApk.getName() + "  [" + verify + "]");
            if (choice == JOptionPane.YES_OPTION) installApk();
        });
    }

    private void rebuildApk() {
        if (project == null) { needProject(); return; }
        runPipeline("Rebuilding signed APK…", p -> {
            lastBuiltApk = project.getSmaliPatcher().buildSignedApk(p::publishMsg);
            return null;
        }, ok -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Patched APK built:\n" + lastBuiltApk.getAbsolutePath()
                            + "\n\nInstall it now via adb?",
                    "Build Complete", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            setStatus("Built: " + lastBuiltApk.getAbsolutePath());
            if (choice == JOptionPane.YES_OPTION) installApk();
        });
    }

    private void installApk() {
        if (project == null) { needProject(); return; }
        if (lastBuiltApk == null || !lastBuiltApk.isFile()) {
            JOptionPane.showMessageDialog(this, "No patched APK yet. Run Build → Rebuild Signed APK first.");
            return;
        }
        runPipeline("Installing via adb…", p -> {
            project.getSmaliPatcher().install(null, lastBuiltApk, p::publishMsg);
            return null;
        }, ok -> setStatus("Installed " + lastBuiltApk.getName()));
    }

    private void needProject() {
        JOptionPane.showMessageDialog(this, "Open an APK first (File → Open APK).");
    }

    /** About dialog: what Mythosaur is, plus respectful credit to the open-source projects. */
    private void showAbout() {
        String bg = hex(Theme.BG), surf = hex(Theme.SURFACE), text = hex(Theme.TEXT),
               muted = hex(Theme.MUTED), accent = hex(Theme.ACCENT), cyan = hex(Theme.CYAN);

        String html = "<html><body style='font-family:sans-serif; background:" + bg
                + "; color:" + text + "; padding:14px; width:520px'>"
                + "<div style='font-size:22px; font-weight:bold; color:" + accent + "'>Mythosaur RE</div>"
                + "<div style='color:" + muted + "; font-size:12px; margin-top:2px'>"
                + "Dedicated open-source APK reverse-engineering IDE &nbsp;·&nbsp; v1.0.0</div>"
                + "<div style='color:" + text + "; font-size:12px; margin-top:6px'>"
                + "Created by <b style='color:" + accent + "'>durgeshkt</b></div>"

                + "<p style='font-size:13px; line-height:1.5; margin-top:14px'>"
                + "Mythosaur RE brings the Java, smali, native, and protection layers of an Android "
                + "application into one unified workspace — static analysis, deobfuscation assistance, "
                + "patching with re-signing, and on-device debugging — built in pure Java.</p>"

                + "<div style='font-size:15px; font-weight:bold; color:" + cyan + "; margin-top:16px'>"
                + "Acknowledgements</div>"
                + "<p style='font-size:12px; color:" + muted + "; margin:6px 0'>"
                + "Mythosaur stands on the shoulders of the open-source community. With gratitude and "
                + "respect, we credit the following projects and their authors:</p>"
                + "<ul style='font-size:12px; line-height:1.7; margin-top:4px'>"
                + cred("jadx", "Skylot &amp; contributors", "Apache-2.0")
                + cred("smali · baksmali · dexlib2", "Ben Gruver &amp; contributors", "BSD-3-Clause")
                + cred("Apktool", "Connor Tumbleson, Ryszard Wiśniewski &amp; contributors", "Apache-2.0")
                + cred("CFR", "Lee Benfield", "MIT")
                + cred("Vineflower", "Vineflower contributors", "Apache-2.0")
                + cred("Procyon", "Mike Strobel", "Apache-2.0")
                + cred("dex2jar", "Bob Pan (pxb1988) &amp; contributors", "Apache-2.0")
                + cred("FlatLaf", "FormDev Software GmbH", "Apache-2.0")
                + cred("RSyntaxTextArea", "Robert Futrell · Fifesoft", "modified BSD")
                + "</ul>"
                + "<p style='font-size:12px; line-height:1.5; color:" + muted + "'>"
                + "Thank you to every author and contributor of these projects, and to the wider "
                + "Android reverse-engineering community. Mythosaur would not exist without your work. "
                + "Each component is used under and remains subject to its own license.</p>"

                + "<div style='font-size:15px; font-weight:bold; color:" + cyan + "; margin-top:12px'>License</div>"
                + "<p style='font-size:12px; color:" + muted + "'>Mythosaur RE is released under the MIT License.</p>"
                + "</body></html>";

        JEditorPane pane = new JEditorPane("text/html", html);
        pane.setEditable(false);
        pane.setBackground(Theme.BG);
        pane.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(560, 560));

        JDialog dialog = new JDialog(this, "About Mythosaur RE", true);
        dialog.getContentPane().setBackground(Theme.BG);
        dialog.add(scroll, BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(ev -> dialog.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.setBackground(Theme.SURFACE);
        south.add(close);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static String cred(String project, String authors, String license) {
        return "<li><b>" + project + "</b> &nbsp;—&nbsp; " + authors
                + " &nbsp;<span style='color:" + hex(Theme.MUTED) + "'>· " + license + "</span></li>";
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Set the window/taskbar icon from the bundled multi-size PNGs. */
    private void loadAppIcons() {
        java.util.List<Image> icons = new java.util.ArrayList<>();
        for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            try (java.io.InputStream in = getClass().getResourceAsStream("/icon-" + s + ".png")) {
                if (in != null) icons.add(javax.imageio.ImageIO.read(in));
            } catch (Exception ignored) {}
        }
        if (!icons.isEmpty()) {
            setIconImages(icons);
            // also set the macOS/Linux taskbar icon where supported
            try { Taskbar.getTaskbar().setIconImage(icons.get(icons.size() - 1)); }
            catch (Throwable ignored) {}
        }
    }

    /** True if the directory exists and contains at least one entry. */
    private static boolean hasFiles(java.nio.file.Path dir) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return false;
        try (var s = java.nio.file.Files.list(dir)) { return s.findAny().isPresent(); }
        catch (Exception e) { return false; }
    }

    /** Human-readable one-liner for an exception — falls back to the type when there's no message. */
    private static String describe(Throwable t) {
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.getClass().getSimpleName();
    }

    /** Small SwingWorker runner with a progress dialog, for the patch pipeline. */
    private interface PipelineStep { Object run(PipelineProgress p) throws Exception; }

    final class PipelineProgress {
        private java.util.function.Consumer<String> sink;
        void publishMsg(String m) { if (sink != null) sink.accept(m); }
    }

    private void runPipeline(String title, PipelineStep step, java.util.function.Consumer<Object> onDone) {
        JDialog dialog = buildProgressDialog();
        dialog.setTitle(title);
        JLabel msg = (JLabel) dialog.getContentPane().getComponent(1);

        SwingWorker<Object, String> worker = new SwingWorker<>() {
            protected Object doInBackground() throws Exception {
                PipelineProgress pp = new PipelineProgress();
                pp.sink = this::publish;
                return step.run(pp);
            }
            protected void process(java.util.List<String> chunks) {
                if (!chunks.isEmpty()) msg.setText(chunks.get(chunks.size() - 1));
            }
            protected void done() {
                dialog.dispose();
                try {
                    Object r = get();
                    onDone.accept(r);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    String m = describe(c);
                    JOptionPane.showMessageDialog(MainWindow.this,
                            m, "Pipeline Error", JOptionPane.ERROR_MESSAGE);
                    setStatus("Error: " + m);
                }
            }
        };
        worker.execute();
        dialog.setVisible(true);
    }

    private void switchTheme(boolean dark) {
        Theme.apply(dark);
        // re-theme every window + popup (FlatLaf restyles standard components)
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
        }
        // re-theme open code/smali editors (RSyntaxTextArea is styled at creation)
        if (codeView != null) codeView.reapplyTheme();
        // refresh status bar colors + repaint custom Java2D views
        statusLabel.setForeground(Theme.MUTED);
        countsLabel.setForeground(Theme.ACCENT);
        repaint();
    }

    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    public void setCounts(String text) {
        countsLabel.setText(text);
    }
}
