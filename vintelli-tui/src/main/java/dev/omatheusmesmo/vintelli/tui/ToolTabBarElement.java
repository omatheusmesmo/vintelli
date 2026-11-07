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
 * Tab row of a tool window stripe: the shortcut digit and the title of every
 * window on the first row, an accent underline under the selected label on the
 * second. Clicks are answered from the cells this element drew, so
 * {@link #tabAt} agrees with what is on screen.
 *
 * <p>The styles come from the {@code ToolTabBarElement-*} rules of the active
 * theme: {@code shortcut} for the digit, {@code tab} and {@code tab:selected}
 * for the title and {@code underline} for the selection row. The label row is
 * underlined in the frame tone of {@code header-rule}.
 */
final class ToolTabBarElement extends StyledElement<ToolTabBarElement> {

    static final int ROWS = 2;

    private static final int LEAD = 1;
    private static final int GAP = 2;

    record Label(String shortcut, String title) {
    }

    private record Slot(int index, int start, int end) {
    }

    private final List<Label> labels;
    private final int selected;
    private final String underline;
    private volatile List<Slot> slots = List.of();
    private volatile Rect renderedArea = Rect.ZERO;

    ToolTabBarElement(String id, List<Label> labels, int selected, String underline) {
        this.labels = List.copyOf(labels);
        this.selected = this.labels.isEmpty() ? -1 : Math.clamp(selected, 0, this.labels.size() - 1);
        this.underline = underline;
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

        int labelRow = area.y();
        int limit = area.right();
        int x = area.x() + LEAD;
        List<Slot> visible = new ArrayList<>();
        for (int index = 0; index < labels.size() && x < limit; index++) {
            if (index > 0) {
                x += GAP;
            }
            Label label = labels.get(index);
            boolean isSelected = index == selected;
            int start = x;
            x = draw(buffer, x, limit, labelRow, label.shortcut(), shortcutStyle);
            x = draw(buffer, x, limit, labelRow, " " + label.title(), isSelected ? selectedStyle : tabStyle);
            if (isSelected && rows > 1) {
                draw(buffer, start, limit, labelRow + 1, underline.repeat(x - start), underlineStyle);
            }
            visible.add(new Slot(index, start, x));
        }
        buffer.setStyle(new Rect(area.x(), labelRow, area.width(), 1),
                PaneGapElement.headerUnderline(context.childStyle("header-rule").fg().orElse(null)));
        slots = List.copyOf(visible);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth >= 0 ? availableWidth : 1, labels.isEmpty() ? 0 : ROWS);
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
}
