package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorCaretTest {

    private static final Rect AREA = new Rect(0, 0, 50, 6);
    private static final int TEXT_LEFT = 4;
    private static final String TEXT_AREA_FOCUS = BuiltinEditor.TEXT_AREA_ID;

    @TempDir Path project;

    @Test
    void focusedTextAreaDrawsOneCaretCellAtTheCursor() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Caret.java", "first line\nsecond line\nthird line\n", 1);

        Buffer buffer = render(editor, themes, TEXT_AREA_FOCUS);

        assertCaretOn(buffer, TEXT_LEFT, 1, "s");
    }

    @Test
    void caretFollowsArrowKeysAndTyping() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Caret.java", "first line\nsecond line\nthird line\n", 1);
        render(editor, themes, TEXT_AREA_FOCUS);

        editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        assertCaretOn(render(editor, themes, TEXT_AREA_FOCUS), TEXT_LEFT + 2, 1, "c");

        editor.handleKey(KeyEvent.ofChar('X'));
        assertEquals("seXcond line", editor.document().state().getLine(1));
        assertCaretOn(render(editor, themes, TEXT_AREA_FOCUS), TEXT_LEFT + 3, 1, "c");
    }

    @Test
    void noCaretWhenAnotherPaneOrAnyNonTypingElementHasFocus() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Caret.java", "first line\nsecond line\n", 1);

        for (String focus : new String[] {null, "project-panel", "tool-run", "editor-navigation-bar",
                "editor-close-confirmation"}) {
            assertEquals(0, reversedCells(render(editor, themes, focus)).size(),
                    "no caret is drawn while the focus is " + focus);
        }
    }

    @Test
    void editorPanelAndEditorContentFocusDrawTheCaret() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Caret.java", "first line\nsecond line\n", 1);

        assertCaretOn(render(editor, themes, "editor-panel"), TEXT_LEFT, 1, "s");
        assertCaretOn(render(editor, themes, "editor-content"), TEXT_LEFT, 1, "s");
    }

    @Test
    void caretAtEndOfLineIsDrawnOnTheBlankCellAfterTheText() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "End.txt", "abc\nxyz\n", 0);
        editor.handleKey(KeyEvent.ofKey(KeyCode.END));

        assertCaretOn(render(editor, themes, TEXT_AREA_FOCUS), TEXT_LEFT + 3, 0, " ");
    }

    @Test
    void caretOnAnEmptyLineIsDrawnAtTheTextStart() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Empty.txt", "abc\n\nxyz\n", 1);

        assertCaretOn(render(editor, themes, TEXT_AREA_FOCUS), TEXT_LEFT, 1, " ");
    }

    @Test
    void verticallyScrolledDocumentPlacesTheCaretOnTheScrolledRow() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= 40; line++) {
            text.append("line ").append(line).append('\n');
        }
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Long.txt", text.toString(), 30);

        Buffer buffer = render(editor, themes, TEXT_AREA_FOCUS);

        int scrollRow = editor.document().state().scrollRow();
        assertTrue(scrollRow > 0, "the document scrolled to keep the caret visible");
        int gutterWidth = Integer.toString(editor.document().lineCount()).length() + 3;
        assertCaretOn(buffer, gutterWidth, 30 - scrollRow, "l");
    }

    @Test
    void horizontallyScrolledLineKeepsTheCaretOnItsCharacter() throws IOException {
        String line = "0123456789".repeat(12);
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Wide.txt", line + "\n", 0);
        for (int step = 0; step < 105; step++) {
            editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        }

        Buffer buffer = render(editor, themes, TEXT_AREA_FOCUS);

        int scrollCol = editor.document().state().scrollCol();
        assertTrue(scrollCol > 0, "the line scrolled horizontally");
        assertCaretOn(buffer, TEXT_LEFT + (105 - scrollCol), 0, "5");
    }

    @Test
    void foldedImportsMapTheCaretToTheCollapsedRow() throws IOException {
        String source = "package a;\n\nimport x.A;\nimport x.B;\n\nclass C {\n";
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Folded.java", source, 5);
        assertTrue(editor.toggleImportFold(), "the import block folds");
        for (int step = 0; step < 6; step++) {
            editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        }

        assertCaretOn(render(editor, themes, TEXT_AREA_FOCUS), TEXT_LEFT + 6, 4, "C");
    }

    @Test
    void wideUnicodeCharactersBeforeTheCaretShiftItByTheirDisplayWidth() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Wide.txt", "中文ab\n", 0);
        editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));

        assertCaretOn(render(editor, themes, TEXT_AREA_FOCUS), TEXT_LEFT + 4, 0, "a");
    }

    @Test
    void tabBeforeTheCaretIsMeasuredTheSameWayTheTextAreaDrawsIt() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Tab.txt", "a\tb\n", 0);
        editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));

        Buffer buffer = render(editor, themes, TEXT_AREA_FOCUS);

        assertEquals("b", caretCell(buffer).symbol());
    }

    @Test
    void caretStaysVisibleOnTheCaretRowAndTheRowKeepsItsBackground() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Caret.java", "first\nsecond\n", 1);
        Color caretRow = themes.color("editor-caret-row");

        Buffer buffer = render(editor, themes, TEXT_AREA_FOCUS);

        assertCaretOn(buffer, TEXT_LEFT, 1, "s");
        assertEquals(caretRow, buffer.get(TEXT_LEFT, 1).style().bg().orElse(null),
                "the caret cell keeps the caret row background");
        assertEquals(caretRow, buffer.get(TEXT_LEFT + 8, 1).style().bg().orElse(null),
                "the rest of the caret row keeps its background");
    }

    @Test
    void selectedCellsKeepTheirLookWhileTheCaretSitsOutsideTheSelection() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Caret.java", "first line\nsecond line\n", 1);
        editor.document().highlighter().setSelection(new SelectionCursor(1, 0, 1, 3));
        Color caretRow = themes.color("editor-caret-row");
        for (int step = 0; step < 3; step++) {
            editor.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        }

        Buffer buffer = render(editor, themes, TEXT_AREA_FOCUS);

        assertTrue(buffer.get(TEXT_LEFT + 3, 1).style().addModifiers().contains(Modifier.REVERSED),
                "the caret cell after the selection is drawn");
        assertEquals("o", buffer.get(TEXT_LEFT + 3, 1).symbol(), "the caret sits on the character after the selection");
        for (int x = TEXT_LEFT; x < TEXT_LEFT + 3; x++) {
            assertTrue(buffer.get(x, 1).style().addModifiers().contains(Modifier.REVERSED),
                    "a selected cell keeps the reversed selection at column " + x);
            assertFalse(caretRow.equals(buffer.get(x, 1).style().bg().orElse(null)),
                    "a selected cell is not painted with the caret row at column " + x);
        }
    }

    @Test
    void movingTheCaretLeavesTheDocumentCleanAndAddsNoUndoStep() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Caret.java", "first line\nsecond line\n", 0);
        for (KeyCode code : new KeyCode[] {KeyCode.RIGHT, KeyCode.DOWN, KeyCode.END, KeyCode.HOME, KeyCode.LEFT}) {
            editor.handleKey(KeyEvent.ofKey(code));
            render(editor, themes, TEXT_AREA_FOCUS);
        }

        assertFalse(editor.activeDocument().orElseThrow().dirty(), "moving the caret does not dirty the document");
        assertFalse(editor.undo(), "moving the caret adds no undo entry");
    }

    @Test
    void userThemeWithoutTheCaretRoleStillShowsTheCaret() throws IOException {
        Path userFile = project.resolve("themes.tcss");
        Files.writeString(userFile, "@theme night\n" + darculaWithoutCaretRole());
        ShellThemes themes = new ShellThemes("night", userFile);
        assertFalse(themes.variablesOf("night").isEmpty(), "the user theme loads");
        BuiltinEditor editor = open(themes, "Caret.java", "first line\nsecond line\n", 1);

        assertCaretOn(render(editor, themes, TEXT_AREA_FOCUS), TEXT_LEFT, 1, "s");
    }

    private BuiltinEditor open(ShellThemes themes, String name, String text, int row) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, text);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, row));
        return editor;
    }

    private static String darculaWithoutCaretRole() throws IOException {
        String css;
        try (InputStream input = EditorCaretTest.class.getResourceAsStream(
                "/dev/omatheusmesmo/vintelli/tui/themes/darcula.tcss")) {
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        String role = ".editor-caret {\n  text-style: reversed;\n}\n";
        assertTrue(css.contains(role), "the shipped darcula theme declares the caret role");
        return css.replace(role, "");
    }

    private static Buffer render(BuiltinEditor editor, ShellThemes themes, String focusedId) {
        Buffer buffer = Buffer.empty(AREA);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        if (focusedId != null) {
            context.focusManager().setFocus(focusedId);
        }
        editor.element().render(Frame.forTesting(buffer), AREA, context);
        return buffer;
    }

    private static List<int[]> reversedCells(Buffer buffer) {
        List<int[]> cells = new ArrayList<>();
        for (int y = AREA.y(); y < AREA.y() + AREA.height(); y++) {
            for (int x = AREA.x(); x < AREA.x() + AREA.width(); x++) {
                if (buffer.get(x, y).style().addModifiers().contains(Modifier.REVERSED)) {
                    cells.add(new int[] {x, y});
                }
            }
        }
        return cells;
    }

    private static Cell caretCell(Buffer buffer) {
        List<int[]> cells = reversedCells(buffer);
        assertEquals(1, cells.size(), "exactly one caret cell is drawn");
        return buffer.get(cells.get(0)[0], cells.get(0)[1]);
    }

    private static void assertCaretOn(Buffer buffer, int x, int y, String symbol) {
        List<int[]> cells = reversedCells(buffer);
        assertEquals(1, cells.size(), "exactly one caret cell is drawn");
        assertEquals(x, cells.get(0)[0], "the caret column");
        assertEquals(y, cells.get(0)[1], "the caret row");
        assertEquals(symbol, buffer.get(x, y).symbol(), "the caret sits on the character the cursor precedes");
    }
}
