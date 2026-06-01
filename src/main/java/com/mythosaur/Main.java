package com.mythosaur;

import com.mythosaur.ui.MainWindow;
import com.mythosaur.ui.TermsDialog;
import com.mythosaur.util.Theme;

import javax.swing.*;

/**
 * Mythosaur RE — a dedicated open-source APK reverse-engineering IDE.
 * Embeds jadx (decompile) and dexlib2 (DEX analysis) as libraries.
 */
public class Main {

    public static void main(String[] args) {
        // Quiet jadx's decompiler chatter — heavily-obfuscated apps emit thousands of
        // harmless "Code restructure failed" WARNs and per-method JadxOverflow/StackOverflow
        // ERRORs (jadx falls back to raw code). Silence the whole jadx logger.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        System.setProperty("org.slf4j.simpleLogger.log.jadx", "off");
        System.setProperty("flatlaf.useWindowDecorations", "false");

        boolean startDark = !"light".equalsIgnoreCase(System.getProperty("theme", "dark"));
        SwingUtilities.invokeLater(() -> {
            Theme.apply(startDark);
            // First-run Terms & Conditions — must be accepted to proceed.
            if (!TermsDialog.ensureAccepted(null)) {
                System.exit(0);
                return;
            }
            MainWindow window = new MainWindow();
            window.setVisible(true);
            if (args.length > 0) {
                window.openApk(new java.io.File(args[0]));
            }
        });
    }
}
