package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Span;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;

import java.util.List;

/**
 * One project tree row drawn as styled spans, so the icon can carry its own
 * colour and the dependency scope its dim style. Each span is patched onto the
 * base style of the row and painted cell by cell, as the editor tab bar paints
 * its icons: a named (ANSI) colour is kept, where a styled text child would be
 * overridden by the colour the theme gives the row.
 */
final class ProjectRowElement extends StyledElement<ProjectRowElement> {

    private final List<Span> spans;

    ProjectRowElement(List<Span> spans) {
        this.spans = List.copyOf(spans);
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        Buffer buffer = frame.buffer();
        Style base = context.currentStyle();
        buffer.fill(new Rect(area.x(), area.y(), area.width(), 1), new Cell(" ", base));
        int x = area.x();
        for (Span span : spans) {
            x = draw(buffer, x, area.right(), area.y(), span.content(), base.patch(span.style()));
        }
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        int width = 0;
        for (Span span : spans) {
            width += CharWidth.of(span.content());
        }
        return Size.of(width, 1);
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
