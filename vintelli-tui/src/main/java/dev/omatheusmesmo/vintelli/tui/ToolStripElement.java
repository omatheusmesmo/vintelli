package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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
 * Two column icon strip at the edge of the shell: one icon per tool window of
 * its side. The icons stack from the top, and the icons flagged as bottom
 * anchored stack from the bottom of the strip, as the left strip does for the
 * bottom windows. The active icon takes the {@code icon:selected} style, the
 * others the {@code icon} style, both from the {@code ToolStripElement-*} rules.
 */
final class ToolStripElement extends StyledElement<ToolStripElement> {

    static final int WIDTH = 2;

    record Icon(String id, String glyph, boolean active, boolean bottomAnchored) {
    }

    private record Slot(String id, int row) {
    }

    private final Supplier<List<Icon>> icons;
    private volatile Rect renderedArea = Rect.ZERO;
    private volatile List<Slot> slots = List.of();

    ToolStripElement(String id, Supplier<List<Icon>> icons) {
        this.icons = icons;
        id(id);
        length(WIDTH);
    }

    /** The icon drawn on the row of the click, or empty outside the strip. */
    Optional<String> iconAt(int x, int y) {
        Rect area = renderedArea;
        if (area.isEmpty() || x < area.x() || x >= area.right()) {
            return Optional.empty();
        }
        return slots.stream().filter(slot -> slot.row() == y).map(Slot::id).findFirst();
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        renderedArea = area;
        slots = List.of();
        if (area.isEmpty()) {
            return;
        }
        Buffer buffer = frame.buffer();
        buffer.fill(area, new Cell(" ", context.currentStyle()));
        List<Icon> items = icons.get();
        int end = area.y() + area.height();
        List<Slot> placed = new ArrayList<>();
        int row = area.y();
        for (Icon icon : items) {
            if (!icon.bottomAnchored() && row < end) {
                draw(buffer, area, row, icon, context, placed);
                row++;
            }
        }
        List<Icon> bottom = items.stream().filter(Icon::bottomAnchored).toList();
        int bottomRow = Math.max(row, end - bottom.size());
        for (Icon icon : bottom) {
            if (bottomRow < end) {
                draw(buffer, area, bottomRow, icon, context, placed);
            }
            bottomRow++;
        }
        slots = List.copyOf(placed);
    }

    private static void draw(Buffer buffer, Rect area, int row, Icon icon, RenderContext context,
            List<Slot> placed) {
        Style style = icon.active()
                ? context.childStyle("icon", PseudoClassState.ofSelected())
                : context.childStyle("icon");
        String cell = CharWidth.substringByWidth(" " + icon.glyph() + " ", area.width());
        buffer.setString(area.x(), row, cell, style);
        placed.add(new Slot(icon.id(), row));
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(WIDTH, availableHeight >= 0 ? availableHeight : 1);
    }
}
