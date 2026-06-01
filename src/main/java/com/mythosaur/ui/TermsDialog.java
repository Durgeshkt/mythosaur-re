package com.mythosaur.ui;

import com.mythosaur.util.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * First-run Terms &amp; Conditions gate. Shown on every platform (so acceptance is
 * guaranteed regardless of how the app was installed) until the user accepts the current
 * version; the acceptance is remembered in {@code ~/.mythosaur/.terms-accepted}.
 *
 * <p>The native installers additionally present the same terms during installation via
 * jpackage's license page.
 */
public final class TermsDialog {

    /** Bump when the terms change to re-prompt accepted users. */
    public static final String VERSION = "1.0";

    private TermsDialog() {}

    private static Path flagFile() {
        return Path.of(System.getProperty("user.home"), ".mythosaur", ".terms-accepted");
    }

    public static boolean alreadyAccepted() {
        try {
            Path f = flagFile();
            return Files.isRegularFile(f) && Files.readString(f).trim().startsWith(VERSION);
        } catch (Exception e) {
            return false;
        }
    }

    /** Show the terms (if not yet accepted) and return true only if the user agrees. */
    public static boolean ensureAccepted(Component parent) {
        if (alreadyAccepted()) return true;

        JTextArea area = new JTextArea(loadTerms());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.mono(12));
        area.setBackground(Theme.SURFACE);
        area.setForeground(Theme.TEXT);
        area.setCaretColor(Theme.TEXT);
        area.setBorder(new EmptyBorder(10, 12, 10, 12));
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(660, 460));
        scroll.setBorder(null);

        JLabel header = new JLabel("Please review and accept the Terms & Conditions to continue");
        header.setFont(Theme.sansBold(13));
        header.setForeground(Theme.ACCENT);
        header.setBorder(new EmptyBorder(12, 14, 8, 14));

        JDialog dialog = new JDialog((Frame) null, "Mythosaur RE — Terms & Conditions", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);  // force an explicit choice
        dialog.getContentPane().setBackground(Theme.BG);
        dialog.add(header, BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);

        final boolean[] accepted = {false};
        JButton agree = new JButton("I Agree");
        JButton decline = new JButton("Decline & Exit");
        agree.addActionListener(e -> { accepted[0] = true; writeAccepted(); dialog.dispose(); });
        decline.addActionListener(e -> { accepted[0] = false; dialog.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        south.setBackground(Theme.SURFACE);
        south.add(decline);
        south.add(agree);
        dialog.add(south, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.getRootPane().setDefaultButton(agree);
        dialog.setVisible(true);
        return accepted[0];
    }

    private static String loadTerms() {
        try (InputStream in = TermsDialog.class.getResourceAsStream("/TERMS.txt")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return "Mythosaur RE — Terms & Conditions\n\n"
                + "This software is for authorized reverse-engineering, security research, and\n"
                + "education only. Use it only on applications you own or are authorized to analyze.\n"
                + "It is provided \"as is\", without warranty of any kind. By clicking \"I Agree\" you\n"
                + "accept these terms.";
    }

    private static void writeAccepted() {
        try {
            Path f = flagFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, VERSION + " accepted " + Instant.now());
        } catch (Exception ignored) {}
    }
}
