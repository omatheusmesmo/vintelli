package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The title of a pane header that has no rule under it is the footer of the
 * project pane. A footer wider than its pane ends with an ellipsis inside the
 * pane instead of running into the next island; a ruled header keeps its text.
 */
class PaneHeaderTest {

    private static final Rect AREA = new Rect(0, 0, 20, 2);
    private static final String LONG_TITLE = "Read File: local (local filesystem, confined to the project root)";

    @Test
    void anUnruledTitleThatDoesNotFitEndsWithAnEllipsisInsideItsArea() {
        Buffer buffer = paint(LONG_TITLE, false);

        String row = row(buffer, AREA.width());
        assertEquals("…", row.substring(AREA.width() - 1), "the last cell of the footer holds the ellipsis: " + row);
        assertTrue(row.startsWith("  Read File: local"), "the start of the title is kept: " + row);
        assertEquals("", row(buffer, buffer.width()).substring(AREA.width()).strip(), "nothing is drawn past the area");
    }

    @Test
    void anUnruledTitleThatFitsIsDrawnWhole() {
        String row = row(paint("Read File: local", false), AREA.width());

        assertEquals("  Read File: local", row.stripTrailing());
        assertFalse(row.contains("…"), row);
    }

    @Test
    void aRuledHeaderKeepsItsFullTitle() {
        String row = row(paint(LONG_TITLE, true), AREA.width());

        assertFalse(row.contains("…"), "a ruled header is not clipped with an ellipsis: " + row);
    }

    private static Buffer paint(String title, boolean ruled) {
        Buffer buffer = Buffer.empty(new Rect(0, 0, 40, 2));
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        PaneHeader.paint(Frame.forTesting(buffer), AREA, title, false, themes, " ", ruled);
        return buffer;
    }

    private static String row(Buffer buffer, int width) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < width; x++) {
            text.append(buffer.get(x, 0).symbol());
        }
        return text.toString();
    }
}
