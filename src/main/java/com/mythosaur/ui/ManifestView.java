package com.mythosaur.ui;

import com.mythosaur.core.Project;
import com.mythosaur.util.Theme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;

/** Shows the decoded AndroidManifest.xml (read-only, XML-highlighted). */
public class ManifestView extends JPanel {

    public ManifestView(Project project) {
        setLayout(new BorderLayout());
        setBackground(Theme.BG);

        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
        area.setEditable(false);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setFont(Theme.mono(12));
        applyTheme(area);

        String xml = project.getManifest().getRawXml();
        area.setText(xml == null || xml.isBlank()
                ? "<!-- AndroidManifest.xml could not be decoded -->" : xml);
        area.setCaretPosition(0);

        add(new RTextScrollPane(area), BorderLayout.CENTER);
    }

    private void applyTheme(RSyntaxTextArea area) {
        try {
            org.fife.ui.rsyntaxtextarea.Theme t = org.fife.ui.rsyntaxtextarea.Theme.load(
                    getClass().getResourceAsStream(Theme.dark
                            ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                            : "/org/fife/ui/rsyntaxtextarea/themes/idea.xml"));
            if (t != null) t.apply(area);
        } catch (Exception ignored) {}
        area.setBackground(Theme.BG);
        area.setForeground(Theme.TEXT);
        area.setCaretColor(Theme.ACCENT);
    }
}
