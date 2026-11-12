package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;

/**
 * Geometry and chrome shared by the keymap overlays (command palette and help
 * overlay): one centered rounded panel per overlay, cleared before painting
 * and titled after the overlay.
 */
final class OverlayPanel {

    private static final Style TITLE_STYLE = Style.create().bold().gray();

    private OverlayPanel() {
    }

    /**
     * The centered panel rectangle for one overlay on the render surface.
     */
    static Rect centered(Rect area, int minWidth, int maxWidth, int height) {
        int width = Math.min(Math.min(maxWidth, area.width()), Math.max(minWidth, area.width() - 2));
        int clampedHeight = Math.clamp(height, 1, area.height() - 2);
        return new Rect(area.x() + (area.width() - width) / 2,
                area.y() + (area.height() - clampedHeight) / 2, width, clampedHeight);
    }

    /**
     * Clears the panel area and paints the shared chrome; callers render their
     * rows into the returned inner rectangle.
     */
    static Rect renderChrome(Frame frame, Rect panel, String title) {
        frame.renderWidget(Clear.INSTANCE, panel);
        Block block = Block.builder()
                .title(Title.from(Span.styled(" " + title + " ", TITLE_STYLE)))
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .build();
        frame.renderWidget(block, panel);
        return block.inner(panel);
    }

    /**
     * One row of an overlay: the action name, the filler that pushes the chord
     * to the right edge, the active chord and the optional IntelliJ hint.
     */
    record ChordRow(String label, String filler, String chord, String hint) {

        /**
         * Lays out one row within the width. The IntelliJ hint is dropped first
         * when the row does not fit with it, and the name is shortened only when
         * the name and the active chord alone do not fit, so the chord always
         * survives.
         */
        static ChordRow fit(String label, String chord, String hint, int width) {
            int room = width - 2;
            boolean withHint = !hint.isEmpty() && label.length() + chord.length() + 1 + hint.length() <= room;
            String name = label.length() + chord.length() <= room ? label : truncate(label, room - chord.length());
            int used = 1 + name.length() + chord.length() + (withHint ? 1 + hint.length() : 0);
            return new ChordRow(name, " ".repeat(Math.max(1, width - used)), chord, withHint ? hint : "");
        }
    }

    private static String truncate(String value, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        return value.length() <= maxWidth ? value : value.substring(0, maxWidth);
    }
}
