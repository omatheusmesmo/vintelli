package dev.omatheusmesmo.vintelli.tui;

import java.util.List;
import java.util.function.BooleanSupplier;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.ElementRegistry;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;

/**
 * Paints the frame between the pane islands once the shell layout is drawn.
 * The panes are islands of the {@code $editor-bg} tone on the {@code $panel}
 * frame, one cell apart. In Unicode a gap cell is a half block in the island
 * tone over the frame tone, so a gap reads as half a cell. A corner where a gap
 * turns is a quarter block in the frame tone over the island tone. Without
 * Unicode a gap is a plain frame cell and no corner is drawn.
 *
 * <p>The gap cells are the rendered areas of the separator elements, which keep
 * the splitter hit areas. The islands and the separators are read from the
 * element registry by id, so a hidden pane or a hidden bottom pane leaves no gap
 * and no corner behind.
 */
final class PaneGapElement implements Element {

    static final String VERTICAL_SEPARATOR = "pane-separator-vertical";
    static final String RIGHT_SEPARATOR = "pane-separator-right";
    static final String HORIZONTAL_SEPARATOR = "pane-separator-horizontal";

    private static final List<String> ISLANDS = List.of("project-pane", "editor-panel", "bottom-stripe",
            "right-tool-stripe");
    private static final String HALF_RIGHT = "▐";
    private static final String HALF_LEFT = "▌";
    private static final String HALF_BOTTOM = "▄";
    private static final String QUARTER_TOP_LEFT = "▘";
    private static final String QUARTER_TOP_RIGHT = "▝";
    private static final String QUARTER_BOTTOM_LEFT = "▖";
    private static final String QUARTER_BOTTOM_RIGHT = "▗";
    private static final String PLAIN = " ";

    private final Element child;
    private final ShellThemes themes;
    private final BooleanSupplier unicode;

    PaneGapElement(Element child, ShellThemes themes, BooleanSupplier unicode) {
        this.child = child;
        this.themes = themes;
        this.unicode = unicode;
    }

    /**
     * The underline of a pane header row: the cells underlined in the frame
     * tone, where the terminal supports an underline color.
     */
    static Style headerUnderline(Color frame) {
        return Style.EMPTY.underlined().underlineColor(frame);
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        child.render(frame, area, context);
        if (context instanceof DefaultRenderContext drc) {
            paint(frame.buffer(), drc.elementRegistry());
        }
    }

    private void paint(Buffer buffer, ElementRegistry registry) {
        Rect vertical = areaOf(registry, VERTICAL_SEPARATOR);
        Rect right = areaOf(registry, RIGHT_SEPARATOR);
        Rect horizontal = areaOf(registry, HORIZONTAL_SEPARATOR);
        boolean glyphs = unicode.getAsBoolean();
        int gapRow = horizontal.isEmpty() ? -1 : horizontal.y();
        Style gap = themes.resolver("pane-gap").toStyle();
        paintVerticalGap(buffer, vertical, glyphs ? HALF_RIGHT : PLAIN, gapRow, gap);
        paintVerticalGap(buffer, right, glyphs ? HALF_LEFT : PLAIN, gapRow, gap);
        if (!horizontal.isEmpty()) {
            for (int x = horizontal.x(); x < horizontal.right(); x++) {
                buffer.setString(x, horizontal.y(), glyphs ? HALF_BOTTOM : PLAIN, gap);
            }
        }
        if (!glyphs) {
            return;
        }
        List<Rect> gaps = List.of(vertical, right, horizontal);
        List<Rect> islands = ISLANDS.stream().map(id -> areaOf(registry, id)).filter(area -> !area.isEmpty()).toList();
        Style corner = themes.resolver("pane-corner").toStyle();
        for (Rect island : islands) {
            int lastColumn = island.right() - 1;
            int lastRow = island.bottom() - 1;
            paintCorner(buffer, islands, gaps, corner, island.x(), island.y(), -1, -1, QUARTER_TOP_LEFT);
            paintCorner(buffer, islands, gaps, corner, lastColumn, island.y(), 1, -1, QUARTER_TOP_RIGHT);
            paintCorner(buffer, islands, gaps, corner, island.x(), lastRow, -1, 1, QUARTER_BOTTOM_LEFT);
            paintCorner(buffer, islands, gaps, corner, lastColumn, lastRow, 1, 1, QUARTER_BOTTOM_RIGHT);
        }
    }

    private static void paintVerticalGap(Buffer buffer, Rect gap, String glyph, int gapRow, Style style) {
        if (gap.isEmpty()) {
            return;
        }
        for (int y = gap.y(); y < gap.bottom(); y++) {
            buffer.setString(gap.x(), y, y == gapRow ? PLAIN : glyph, style);
        }
    }

    /**
     * Draws the corner of an island in its own cell, and only where that cell
     * is blank, so no letter is ever covered. A corner between two gap cells is
     * a junction; any other corner meets the toolbar, the status row, a stripe
     * or the screen edge. A corner whose outward step meets another island is
     * never drawn.
     */
    private static void paintCorner(Buffer buffer, List<Rect> islands, List<Rect> gaps, Style style, int x, int y,
            int dx, int dy, String glyph) {
        List<int[]> outward = List.of(new int[] {x + dx, y + dy}, new int[] {x + dx, y}, new int[] {x, y + dy});
        if (outward.stream().anyMatch(cell -> isCellIn(islands, cell[0], cell[1]))) {
            return;
        }
        boolean junction = outward.stream().allMatch(cell -> isCellIn(gaps, cell[0], cell[1]));
        boolean outer = outward.stream().anyMatch(cell -> !isCellIn(gaps, cell[0], cell[1]));
        boolean blank = buffer.get(x, y).symbol().equals(PLAIN);
        if (blank && (junction || outer)) {
            buffer.setString(x, y, glyph, buffer.get(x, y).style().patch(style));
        }
    }

    private static boolean isCellIn(List<Rect> rects, int x, int y) {
        return rects.stream().anyMatch(rect -> !rect.isEmpty() && x >= rect.x() && x < rect.right()
                && y >= rect.y() && y < rect.bottom());
    }

    private static Rect areaOf(ElementRegistry registry, String id) {
        Rect area = registry.getArea(id);
        return area == null ? Rect.ZERO : area;
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return child.preferredSize(availableWidth, availableHeight, context);
    }

    @Override
    public Constraint constraint() {
        return child.constraint();
    }

    @Override
    public String id() {
        return child.id();
    }

    @Override
    public boolean isFocusable() {
        return child.isFocusable();
    }

    @Override
    public Rect renderedArea() {
        return child.renderedArea();
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        return child.handleKeyEvent(event, focused);
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        return child.handleMouseEvent(event);
    }
}
