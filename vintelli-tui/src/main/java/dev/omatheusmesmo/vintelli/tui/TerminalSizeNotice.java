package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.RichTextElement;

/**
 * The notice the shell draws instead of its layout while the terminal is below
 * {@link ShellLayout#MIN_TERMINAL_WIDTH} by {@link ShellLayout#MIN_TERMINAL_HEIGHT}.
 * The text is centred and takes its colour from the {@code shell-notice} class
 * of the theme stylesheets.
 */
final class TerminalSizeNotice {

    static final String ID = "terminal-notice";
    static final String STYLE_CLASS = "shell-notice";

    private TerminalSizeNotice() {
    }

    static boolean tooSmall(Rect area) {
        return area.width() < ShellLayout.MIN_TERMINAL_WIDTH || area.height() < ShellLayout.MIN_TERMINAL_HEIGHT;
    }

    static Element element(Rect area) {
        RichTextElement content = Toolkit.richText(Text.from(
                Line.from("Terminal too small: " + area.width() + "x" + area.height()),
                Line.from("(minimum " + ShellLayout.MIN_TERMINAL_WIDTH + "x" + ShellLayout.MIN_TERMINAL_HEIGHT + ")")))
                .centered();
        content.id(ID);
        content.addClass(STYLE_CLASS);
        return Toolkit.column(Toolkit.spacer(), content, Toolkit.spacer());
    }
}
