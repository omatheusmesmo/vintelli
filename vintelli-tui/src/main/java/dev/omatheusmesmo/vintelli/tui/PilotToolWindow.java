package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Common rendering and key handling for the built-in pilot tool windows: a
 * muted paragraph in a rounded panel titled after the window. Subclasses only
 * describe themselves; the palette comes from {@link VintelliShell} until a
 * theme lands with the styling work.
 */
abstract class PilotToolWindow implements ToolWindow {

    private final String id;
    private final String title;
    private final char shortcut;
    private final int ordinal;
    private final String content;

    PilotToolWindow(String id, String title, char shortcut, int ordinal, String content) {
        this.id = id;
        this.title = title;
        this.shortcut = shortcut;
        this.ordinal = ordinal;
        this.content = content;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String title() {
        return title;
    }

    @Override
    public final char shortcut() {
        return shortcut;
    }

    @Override
    public final int ordinal() {
        return ordinal;
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        return false;
    }

    @Override
    public final void render(Frame frame, Rect area) {
        frame.renderWidget(contentBlock(title, content), area);
    }

    private static Paragraph contentBlock(String title, String content) {
        return Paragraph.builder()
                .text(Text.from(content))
                .style(Style.create().fg(VintelliShell.FOREGROUND))
                .block(VintelliShell.darculaBlock(title, BorderType.ROUNDED))
                .build();
    }
}
