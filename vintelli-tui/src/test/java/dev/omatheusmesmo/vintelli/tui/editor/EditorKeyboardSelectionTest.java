package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorKeyboardSelectionTest {

    private static final String IMPORTS = "package demo;\n\nimport java.util.List;\nimport java.util.Map;\n\n"
            + "import static java.lang.Math.max;\n\nclass A {\n    int x;\n}\n";

    @TempDir Path project;

    @Test
    void shiftArrowsExtendFromTheCaretAndShiftInTheOppositeDirectionShrinkTheSelection() throws IOException {
        BuiltinEditor editor = open("Extend.txt", "alpha\nbravo\n");

        shift(editor, KeyCode.RIGHT, 3);
        assertEquals("alp", selectedText(editor));

        shift(editor, KeyCode.DOWN, 1);
        assertEquals("alpha\nbra", selectedText(editor), "Shift+Down extends by one row, keeping the anchor");

        shift(editor, KeyCode.LEFT, 1);
        assertEquals("alpha\nbr", selectedText(editor), "Shift+Left shrinks the selection again");
        assertFalse(editor.activeDocument().orElseThrow().dirty(), "selecting does not dirty the document");
    }

    @Test
    void aCaretKeyWithoutShiftClearsTheKeyboardSelection() throws IOException {
        BuiltinEditor editor = open("Clear.txt", "alpha beta\n");
        shift(editor, KeyCode.RIGHT, 2);
        assertEquals("al", selectedText(editor));

        assertTrue(editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT)));

        assertEquals("", selectedText(editor), "a plain caret key ends the selection");
        assertEquals(3, editor.pointerState().orElseThrow().column(), "the caret still moved");
    }

    @Test
    void shiftHomeAndShiftEndSelectToTheEdgesOfTheLine() throws IOException {
        BuiltinEditor editor = open("Edges.txt", "alpha beta\n");
        key(editor, KeyCode.RIGHT, 6);

        shift(editor, KeyCode.END, 1);
        assertEquals("beta", selectedText(editor));

        shift(editor, KeyCode.HOME, 1);
        assertEquals("alpha ", selectedText(editor), "Shift+Home selects back to the line start");
    }

    @Test
    void shiftPageDownAndShiftPageUpMoveByTheVisibleRows() throws IOException {
        BuiltinEditor editor = open("Pages.txt", numberedLines(20));
        render(editor, 40, 6);

        shift(editor, KeyCode.PAGE_DOWN, 1);
        assertEquals(5, editor.pointerState().orElseThrow().line(),
                "one page is the five text rows the six-row editor shows");
        assertTrue(selectedText(editor).startsWith("line 1\n"));

        shift(editor, KeyCode.PAGE_UP, 1);
        assertEquals("", selectedText(editor), "Shift+PageUp back to the anchor leaves nothing selected");
    }

    @Test
    void shiftDownAcrossAFoldedImportBlockSelectsTheHiddenLines() throws IOException {
        BuiltinEditor editor = open("A.java", IMPORTS);
        assertTrue(editor.toggleImportFold());
        editor.document().position(1, 0);

        shift(editor, KeyCode.DOWN, 2);
        assertEquals("\nimport java.util.List;\nimport java.util.Map;\n\nimport static java.lang.Math.max;\n",
                selectedText(editor), "the hidden import rows are part of the selection");
        assertTrue(editor.document().importsFolded(), "selecting keeps the import block folded");

        shift(editor, KeyCode.DOWN, 1);
        assertEquals("\nimport java.util.List;\nimport java.util.Map;\n\nimport static java.lang.Math.max;\n\n",
                selectedText(editor));
    }

    @Test
    void ctrlASelectsTheWholeDocumentAndLeavesTheCaretAtTheEnd() throws IOException {
        BuiltinEditor editor = open("All.txt", "alpha\nbeta\n");

        assertTrue(editor.handleKey(KeyEvent.ofChar('a', KeyModifiers.CTRL)));

        assertEquals("alpha\nbeta\n", selectedText(editor));
        assertEquals(2, editor.pointerState().orElseThrow().line(), "the caret sits on the last row");
        assertEquals(0, editor.pointerState().orElseThrow().anchorLine(), "the anchor is the start of the text");
    }

    private BuiltinEditor open(String name, String text) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, text);
        BuiltinEditor editor = new BuiltinEditor(project, null, new ShellThemes("dark", (Path) null)::editorPalette);
        editor.open(new OpenRequest(file, 0));
        return editor;
    }

    private static void render(BuiltinEditor editor, int width, int height) {
        Rect area = new Rect(0, 0, width, height);
        editor.render(Frame.forTesting(Buffer.empty(area)), area);
    }

    private static void shift(BuiltinEditor editor, KeyCode code, int times) {
        for (int index = 0; index < times; index++) {
            assertTrue(editor.handleKey(KeyEvent.ofKey(code, KeyModifiers.SHIFT)), "Shift+" + code + " is handled");
        }
    }

    private static void key(BuiltinEditor editor, KeyCode code, int times) {
        for (int index = 0; index < times; index++) {
            assertTrue(editor.handleKey(KeyEvent.ofKey(code)), code + " is handled");
        }
    }

    private static String selectedText(BuiltinEditor editor) {
        return editor.pointerState().orElseThrow().selectedText();
    }

    private static String numberedLines(int count) {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= count; line++) {
            text.append("line ").append(line).append('\n');
        }
        return text.toString();
    }
}
