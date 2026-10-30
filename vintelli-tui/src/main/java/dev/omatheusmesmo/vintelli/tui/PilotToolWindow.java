package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * Common rendering and key handling for the built-in pilot tool windows: a
 * paragraph in a rounded panel titled after the window. Subclasses only
 * describe themselves; the palette comes from the active theme stylesheet
 * through {@link ShellThemes}, so a theme switch restyles the windows too.
 */
abstract class PilotToolWindow implements ToolWindow {

    private final String id;
    private final String title;
    private final char shortcut;
    private final int ordinal;
    private final String content;
    private final ShellThemes themes;

    PilotToolWindow(String id, String title, char shortcut, int ordinal, String content, ShellThemes themes) {
        this.id = id;
        this.title = title;
        this.shortcut = shortcut;
        this.ordinal = ordinal;
        this.content = content;
        this.themes = themes;
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

    private Paragraph contentBlock(String title, String content) {
        Style contentStyle = themes.resolver("tool-window").toStyle();
        Title blockTitle = Title.from(Span.styled(title, themes.resolver("tool-window-title").toStyle()));
        Block.Builder blockBuilder = Block.builder()
                .title(blockTitle)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .style(contentStyle);
        themes.resolver("tool-window").borderColor().ifPresent(blockBuilder::borderColor);
        return Paragraph.builder()
                .text(Text.from(content))
                .style(contentStyle)
                .block(blockBuilder.build())
                .build();
    }
}
