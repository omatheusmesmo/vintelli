package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.css.cascade.PseudoClassState;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;

/**
 * Tab row of a tool window stripe: the marker and the title of every window on
 * the first row, an accent underline under the selected label on the second.
 * The marker is the window icon in the Nerd Font face and the shortcut digit
 * otherwise. Clicks are answered from the cells this element drew, so
 * {@link #tabAt} agrees with what is on screen.
 *
 * <p>Tabs that do not fit are not drawn, never cut: the row scrolls to keep the
 * selected tab whole, a {@code »} glyph on the right marks more tabs and a
 * {@code «} glyph at the left edge marks tabs scrolled away on the left. Both
 * glyph cells belong to no tab, so a click on them selects nothing.
 *
 * <p>The styles come from the {@code ToolTabBarElement-*} rules of the active
 * theme: {@code shortcut} for the marker, {@code tab} and {@code tab:selected}
 * for the title, {@code underline} for the selection row and {@code overflow}
 * for the glyphs. The label row is underlined in the frame tone of
 * {@code header-rule}.
 */
final class ToolTabBarElement extends StyledElement<ToolTabBarElement> {

    static final int ROWS = 2;

    private static final int LEAD = 1;
    private static final int GAP = 2;
    private static final int OVERFLOW_RESERVE = 2;
    private static final int OVERFLOW_LEFT_RESERVE = 1;

    record Label(String marker, String title) {
    }

    record Glyphs(String underline, String overflowLeft, String overflowRight) {
    }

    private record Window(int first, int last, boolean overflowRight) {
    }

    private record Slot(int index, int start, int end) {
    }

    private final List<Label> labels;
    private final int selected;
    private final Glyphs glyphs;
    private volatile List<Slot> slots = List.of();
    private volatile Rect renderedArea = Rect.ZERO;

    ToolTabBarElement(String id, List<Label> labels, int selected, Glyphs glyphs) {
        this.labels = List.copyOf(labels);
        this.selected = this.labels.isEmpty() ? -1 : Math.clamp(selected, 0, this.labels.size() - 1);
        this.glyphs = glyphs;
        id(id);
        length(ROWS);
    }

    /**
     * The tab under a click on either row of the bar, or -1 for the gaps and
     * everything outside the drawn labels.
     */
    int tabAt(int x, int y) {
        Rect area = renderedArea;
        if (area.isEmpty() || y < area.y() || y >= area.y() + Math.min(ROWS, area.height())) {
            return -1;
        }
        for (Slot slot : slots) {
            if (x >= slot.start() && x < slot.end()) {
                return slot.index();
            }
        }
        return -1;
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        renderedArea = area;
        slots = List.of();
        if (labels.isEmpty() || area.isEmpty()) {
            return;
        }
        Buffer buffer = frame.buffer();
        int rows = Math.min(ROWS, area.height());
        Style base = context.currentStyle();
        buffer.fill(new Rect(area.x(), area.y(), area.width(), rows), new Cell(" ", base));

        Style shortcutStyle = context.childStyle("shortcut");
        Style tabStyle = context.childStyle("tab");
        Style selectedStyle = context.childStyle("tab", PseudoClassState.ofSelected());
        Style underlineStyle = context.childStyle("underline");
        Style overflowStyle = context.childStyle("overflow");

        int labelRow = area.y();
        int[] widths = new int[labels.size()];
        for (int index = 0; index < widths.length; index++) {
            widths[index] = labelWidth(labels.get(index));
        }
        Window window = window(widths, selected, area.width() - LEAD);
        int limit = window.overflowRight() ? area.right() - OVERFLOW_RESERVE : area.right();
        int x = area.x() + LEAD + leftReserve(window.first());
        List<Slot> visible = new ArrayList<>();
        for (int index = window.first(); index <= window.last(); index++) {
            if (index > window.first()) {
                x += GAP;
            }
            Label label = labels.get(index);
            boolean isSelected = index == selected;
            int start = x;
            x = draw(buffer, x, limit, labelRow, label.marker(), shortcutStyle);
            x = draw(buffer, x, limit, labelRow, " " + label.title(), isSelected ? selectedStyle : tabStyle);
            if (isSelected && rows > 1) {
                draw(buffer, start, limit, labelRow + 1, glyphs.underline().repeat(x - start), underlineStyle);
            }
            visible.add(new Slot(index, start, x));
        }
        buffer.setStyle(new Rect(area.x(), labelRow, area.width(), 1),
                PaneGapElement.headerUnderline(context.childStyle("header-rule").fg().orElse(null)));
        if (window.first() > 0) {
            draw(buffer, area.x(), area.x() + 1, labelRow, glyphs.overflowLeft(), overflowStyle);
        }
        if (window.overflowRight()) {
            draw(buffer, area.right() - 1, area.right(), labelRow, glyphs.overflowRight(), overflowStyle);
        }
        slots = List.copyOf(visible);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth >= 0 ? availableWidth : 1, labels.isEmpty() ? 0 : ROWS);
    }

    private static int labelWidth(Label label) {
        return CharWidth.of(label.marker()) + 1 + CharWidth.of(label.title());
    }

    private static int draw(Buffer buffer, int x, int limit, int y, String text, Style style) {
        if (x >= limit) {
            return x;
        }
        String clipped = CharWidth.substringByWidth(text, limit - x);
        if (clipped.isEmpty()) {
            return x;
        }
        buffer.setString(x, y, clipped, style);
        return x + CharWidth.of(clipped);
    }

    /**
     * The tabs shown: the first window, scanning from the left, in which the
     * selected tab fits whole. The row shows the tabs from there on, and marks
     * the right edge when tabs follow that do not fit beside the selection.
     */
    private static Window window(int[] widths, int selected, int region) {
        int count = widths.length;
        for (int first = 0; first <= selected; first++) {
            int budget = region - leftReserve(first);
            if (spanWidth(widths, first, selected) > budget) {
                continue;
            }
            if (spanWidth(widths, first, count - 1) <= budget) {
                return new Window(first, count - 1, false);
            }
            int reserved = budget - OVERFLOW_RESERVE;
            if (spanWidth(widths, first, selected) <= reserved) {
                return new Window(first, lastFitting(widths, first, reserved), true);
            }
        }
        return new Window(selected, selected, selected < count - 1);
    }

    private static int leftReserve(int first) {
        return first > 0 ? OVERFLOW_LEFT_RESERVE : 0;
    }

    private static int lastFitting(int[] widths, int first, int budget) {
        int last = first;
        while (last + 1 < widths.length && spanWidth(widths, first, last + 1) <= budget) {
            last++;
        }
        return last;
    }

    private static int spanWidth(int[] widths, int first, int last) {
        int width = 0;
        for (int index = first; index <= last; index++) {
            width += widths[index] + (index > first ? GAP : 0);
        }
        return width;
    }
}
