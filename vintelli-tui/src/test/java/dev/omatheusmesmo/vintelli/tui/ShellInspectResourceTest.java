package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.ShellInspectResource.EditorRunView;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the payload the render endpoint reports for the editor rows: the
 * neighboring cells that share a foreground color collapse into one run, and
 * every run reports its color in the notation the palette uses, so the app
 * tests can compare a run against {@code colors.editor-keyword} on either
 * color depth. The buffer is filled directly here, so both depths are pinned
 * without a running shell.
 */
class ShellInspectResourceTest {
    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final int FIRST_EDITOR_ROW = 2;
    private static final int LAST_EDITOR_ROW = 12;

    @Test
    void mergesNeighboringCellsOfTheSameColorIntoOneRun() {
        Buffer buffer = Buffer.empty(VIEWPORT);
        Color comment = Color.rgb(0x80, 0x80, 0x80);
        Color keyword = Color.rgb(0xCC, 0x78, 0x32);
        buffer.setString(0, FIRST_EDITOR_ROW, "ab", Style.EMPTY.fg(comment));
        buffer.setString(2, FIRST_EDITOR_ROW, "cd", Style.EMPTY.fg(comment));
        buffer.setString(4, FIRST_EDITOR_ROW, "@param", Style.EMPTY.fg(keyword));

        assertEquals(
                List.of(new EditorRunView(FIRST_EDITOR_ROW, "abcd", "#808080"),
                        new EditorRunView(FIRST_EDITOR_ROW, "@param", "#CC7832")),
                ShellInspectResource.editorRuns(buffer));
    }

    @Test
    void reportsThePaletteNotationOfA256ColorCell() {
        Buffer buffer = Buffer.empty(VIEWPORT);
        buffer.setString(0, LAST_EDITOR_ROW, "text", Style.EMPTY.fg(Color.indexed(237)));

        assertEquals(List.of(new EditorRunView(LAST_EDITOR_ROW, "text", "indexed(237)")),
                ShellInspectResource.editorRuns(buffer));
    }

    @Test
    void reportsCellsWithoutAForegroundColorAndStopsAtTheLastNonBlankCell() {
        Buffer buffer = Buffer.empty(VIEWPORT);
        buffer.setString(0, FIRST_EDITOR_ROW, "plain", Style.EMPTY);
        buffer.setString(5, FIRST_EDITOR_ROW, " x", Style.EMPTY.fg(Color.rgb(0x80, 0x80, 0x80)));

        assertEquals(List.of(new EditorRunView(FIRST_EDITOR_ROW, "plain", null),
                        new EditorRunView(FIRST_EDITOR_ROW, " x", "#808080")),
                ShellInspectResource.editorRuns(buffer));
    }

    @Test
    void reportsRunsOnlyInsideTheEditorWindow() {
        Buffer buffer = Buffer.empty(VIEWPORT);
        buffer.setString(0, FIRST_EDITOR_ROW - 1, "tab", Style.EMPTY.fg(Color.rgb(0xCC, 0x78, 0x32)));
        buffer.setString(0, LAST_EDITOR_ROW + 1, "status", Style.EMPTY.fg(Color.rgb(0xCC, 0x78, 0x32)));

        assertEquals(List.of(), ShellInspectResource.editorRuns(buffer));
    }
}
