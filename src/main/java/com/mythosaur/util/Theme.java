package com.mythosaur.util;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.*;
import java.awt.Color;
import java.awt.Font;

/**
 * Switchable theme palette + FlatLaf setup. Colours are mutable so the custom
 * Java2D views (graphs/CFG) pick up the new palette on their next repaint, while
 * FlatLaf restyles every standard Swing component via {@link #apply}.
 */
public final class Theme {
    private Theme() {}

    public static boolean dark = true;

    // palette (updated by setDark)
    public static Color BG, SURFACE, BORDER, TEXT, MUTED, ACCENT, ACCENT_DIM,
            CYAN, GREEN, RED, PURPLE, ORANGE, NODE, NODE_BORDER;

    static { applyPalette(true); }

    private static void applyPalette(boolean d) {
        dark = d;
        if (d) {
            BG        = new Color(0x0d, 0x11, 0x17);
            SURFACE   = new Color(0x16, 0x1b, 0x22);
            BORDER    = new Color(0x30, 0x36, 0x3d);
            TEXT      = new Color(0xe6, 0xed, 0xf3);
            MUTED     = new Color(0x8b, 0x94, 0x9e);
            ACCENT    = new Color(0xf0, 0xa5, 0x00);
            ACCENT_DIM= new Color(0xb3, 0x7a, 0x00);
            CYAN      = new Color(0x39, 0xd4, 0xe8);
            GREEN     = new Color(0x3f, 0xb9, 0x50);
            RED       = new Color(0xff, 0x6b, 0x6b);
            PURPLE    = new Color(0xbc, 0x8c, 0xff);
            ORANGE    = new Color(0xff, 0xa6, 0x57);
            NODE      = SURFACE;
            NODE_BORDER = BORDER;
        } else {
            BG        = new Color(0xff, 0xff, 0xff);
            SURFACE   = new Color(0xf3, 0xf4, 0xf6);
            BORDER    = new Color(0xd0, 0xd7, 0xde);
            TEXT      = new Color(0x1f, 0x23, 0x28);
            MUTED     = new Color(0x6e, 0x77, 0x81);
            ACCENT    = new Color(0xbf, 0x73, 0x00);
            ACCENT_DIM= new Color(0x8a, 0x52, 0x00);
            CYAN      = new Color(0x09, 0x69, 0xda);
            GREEN     = new Color(0x1a, 0x7f, 0x37);
            RED       = new Color(0xcf, 0x22, 0x2e);
            PURPLE    = new Color(0x82, 0x50, 0xdf);
            ORANGE    = new Color(0xbc, 0x4c, 0x00);
            NODE      = new Color(0xff, 0xff, 0xff);
            NODE_BORDER = BORDER;
        }
    }

    /** Apply (or re-apply) the FlatLaf theme + premium tweaks for the current mode. */
    public static void apply(boolean d) {
        applyPalette(d);

        // premium accent + shape tweaks (apply before LaF install so they stick)
        UIManager.put("Component.focusColor", ACCENT);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("Tree.rowHeight", 24);
        UIManager.put("Table.rowHeight", 24);
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 0));
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabHeight", 30);
        UIManager.put("TabbedPane.selectedBackground", BG);
        UIManager.put("TabbedPane.underlineColor", ACCENT);
        UIManager.put("TabbedPane.hoverColor", SURFACE);
        UIManager.put("TabbedPane.focusColor", SURFACE);
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("MenuItem.selectionArc", 6);
        UIManager.put("Menu.selectionArc", 6);
        UIManager.put("List.selectionArc", 6);
        UIManager.put("Component.accentColor", ACCENT);

        if (d) FlatDarculaLaf.setup(); else FlatIntelliJLaf.setup();

        // re-assert accent after LaF install
        UIManager.put("TabbedPane.underlineColor", ACCENT);
        UIManager.put("Component.accentColor", ACCENT);
        FlatLaf.updateUI();
    }

    public static void toggle() { apply(!dark); }

    public static Font mono(int size)     { return new Font("JetBrains Mono", Font.PLAIN, size).getFamily().equals("JetBrains Mono") ? new Font("JetBrains Mono", Font.PLAIN, size) : new Font(Font.MONOSPACED, Font.PLAIN, size); }
    public static Font monoBold(int size) { return new Font(Font.MONOSPACED, Font.BOLD, size); }
    public static Font sans(int size)     { return new Font(Font.SANS_SERIF, Font.PLAIN, size); }
    public static Font sansBold(int size) { return new Font(Font.SANS_SERIF, Font.BOLD, size); }
}
