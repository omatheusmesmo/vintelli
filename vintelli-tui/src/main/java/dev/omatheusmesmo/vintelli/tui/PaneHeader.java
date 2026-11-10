package dev.omatheusmesmo.vintelli.tui;

import java.util.function.Predicate;
import java.util.function.Supplier;

import dev.tamboui.layout.Rect;
import dev.tamboui.text.CharWidth;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * One-row header drawn inside a borderless pane. The focus state is read from
 * the render context of the pane id, because a sibling header cannot take the
 * {@code :focus} pseudo class of the focused element in TCSS. The header
 * styles come from the {@code pane-header}, {@code pane-header-focused} and
 * {@code pane-marker} roles of the active theme.
 */
final class PaneHeader extends StyledElement<PaneHeader> {

    static final String FOCUS_MARK = "▌";
    static final String FOCUS_MARK_ASCII = "|";

    private final Predicate<RenderContext> focused;
    private final Supplier<String> title;
    private final Supplier<ShellThemes> themes;
    private final Supplier<String> focusMark;
    private final boolean ruled;

    PaneHeader(String paneId, Supplier<String> title, Supplier<ShellThemes> themes, Supplier<String> focusMark) {
        this(paneId, context -> context.isFocused(paneId), title, themes, focusMark, true);
    }

    /**
     * Header whose focus state comes from {@code focused} instead of the render
     * context, for panes such as tool windows that track focus themselves.
     */
    PaneHeader(String paneId, Predicate<RenderContext> focused, Supplier<String> title, Supplier<ShellThemes> themes,
            Supplier<String> focusMark) {
        this(paneId, focused, title, themes, focusMark, true);
    }

    /**
     * Header row of a pane. An unruled header is a footer row, such as the
     * READ_FILE line under the project list, which keeps no underline.
     */
    PaneHeader(String paneId, Predicate<RenderContext> focused, Supplier<String> title, Supplier<ShellThemes> themes,
            Supplier<String> focusMark, boolean ruled) {
        this.focused = focused;
        this.title = title;
        this.themes = themes;
        this.focusMark = focusMark;
        this.ruled = ruled;
        id(paneId + "-header");
        addClass("pane-header-row");
        length(1);
    }

    /**
     * Paints the header on the first row of {@code area} and returns the rows
     * below it, which the pane body uses. A ruled header is underlined over its
     * whole width in the {@code pane-header-rule} color.
     *
     * @return the body rectangle under the header, empty when there is no room
     */
    static Rect paint(Frame frame, Rect area, String title, boolean focused, ShellThemes themes, String focusMark,
            boolean ruled) {
        if (area.isEmpty()) {
            return area;
        }
        Rect header = new Rect(area.x(), area.y(), area.width(), 1);
        Rect body = new Rect(area.x(), area.y() + 1, area.width(), Math.max(0, area.height() - 1));
        Style markerStyle = themes.resolver("pane-marker").toStyle();
        Style titleStyle = themes.resolver(focused ? "pane-header-focused" : "pane-header").toStyle();
        String marker = focused ? focusMark : " ";
        String shown = ruled ? title : ProjectPanel.clipEnd(title, Math.max(1, area.width() - CharWidth.of(marker) - 1));
        Line line = Line.from(
                Span.styled(marker, markerStyle),
                Span.styled(" " + shown, titleStyle));
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), header);
        if (ruled) {
            Color rule = themes.resolver("pane-header-rule").toStyle().fg().orElse(null);
            frame.buffer().setStyle(header, PaneGapElement.headerUnderline(rule));
        }
        return body;
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        paint(frame, area, title.get(), focused.test(context), themes.get(), focusMark.get(), ruled);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth >= 0 ? availableWidth : 1, 1);
    }
}
