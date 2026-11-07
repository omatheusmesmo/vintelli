package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorCaretRowTest {

    private static final int WIDTH = 50;
    private static final int HEIGHT = 6;
    private static final int TEXT_LEFT = 4;

    @TempDir Path project;

    @Test
    void caretRowPaintsTheWholeTextWidthExceptTheSelectedCells() throws IOException {
        Path file = project.resolve("Caret.java");
        Files.writeString(file, "first line\nsecond line\nthird line\n");
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, 1));
        editor.document().highlighter().setSelection(new SelectionCursor(1, 0, 1, 3));
        Color caret = themes.color("editor-caret-row");

        Buffer buffer = render(editor);

        for (int x = TEXT_LEFT; x < TEXT_LEFT + 3; x++) {
            Style selected = buffer.get(x, 1).style();
            assertTrue(selected.addModifiers().contains(Modifier.REVERSED),
                    "a selected cell keeps the reversed selection at column " + x);
            assertFalse(caret.equals(selected.bg().orElse(null)),
                    "a selected cell is not painted with the caret row at column " + x);
        }
        assertEquals(caret, buffer.get(TEXT_LEFT + 3, 1).style().bg().orElse(null),
                "the caret row continues past the selection");
        assertEquals(caret, buffer.get(WIDTH - 1, 1).style().bg().orElse(null),
                "the caret row reaches the right edge of the text width");
        assertFalse(caret.equals(buffer.get(TEXT_LEFT + 10, 0).style().bg().orElse(null)),
                "the row above the caret keeps the plain editor background");
    }

    private static Buffer render(BuiltinEditor editor) {
        Rect area = new Rect(0, 0, WIDTH, HEIGHT);
        Buffer buffer = Buffer.empty(area);
        editor.render(Frame.forTesting(buffer), area);
        return buffer;
    }
}
