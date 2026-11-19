package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorMatchedBraceTest {

    private static final Rect AREA = new Rect(0, 0, 50, 8);
    private static final String ROLE = "editor-matched-brace";
    private static final String TEXT = "foo(bar(1), 2)\n";
    private static final int TEXT_LEFT = 4;

    @TempDir Path project;

    @Test
    void caretAfterTheClosingParenthesisStylesBothCellsInEveryShippedTheme() throws IOException {
        for (String theme : List.of("dark", "darcula")) {
            ShellThemes themes = new ShellThemes(theme, (Path) null);
            BuiltinEditor editor = open(themes, theme + ".java", TEXT);
            editor.handleKey(KeyEvent.ofKey(KeyCode.END));

            Buffer buffer = render(editor, themes);
            Style expected = roleStyle(themes);

            assertTrue(expected.bg().isPresent() && expected.addModifiers().contains(Modifier.BOLD),
                    "the " + ROLE + " role is bold with a background in " + theme);
            assertMatchedBrace(buffer, 3, expected, theme);
            assertMatchedBrace(buffer, 13, expected, theme);
            assertNotEquals(expected.bg(), cellAt(buffer, 7).bg(),
                    "the inner parenthesis is not part of the pair in " + theme);
        }
    }

    @Test
    void movingTheCaretAwayClearsTheMatchedBraceStyle() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "moves.java", TEXT);
        editor.handleKey(KeyEvent.ofKey(KeyCode.END));
        Style expected = roleStyle(themes);
        assertMatchedBrace(render(editor, themes), 3, expected, "dark");

        editor.handleKey(KeyEvent.ofKey(KeyCode.HOME));

        Buffer buffer = render(editor, themes);
        assertNotEquals(expected.bg(), cellAt(buffer, 3).bg(),
                "the pair is gone once the caret leaves the bracket");
    }

    private static void assertMatchedBrace(Buffer buffer, int textColumn, Style expected, String theme) {
        Style style = cellAt(buffer, textColumn);
        assertEquals(expected.bg(), style.bg(),
                "the brace at text column " + textColumn + " has the matched background in " + theme);
        assertTrue(style.addModifiers().contains(Modifier.BOLD),
                "the brace at text column " + textColumn + " is bold in " + theme);
    }

    private static Style cellAt(Buffer buffer, int textColumn) {
        return buffer.get(AREA.x() + TEXT_LEFT + textColumn, AREA.y()).style();
    }

    private BuiltinEditor open(ShellThemes themes, String name, String text) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, text);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, 0));
        return editor;
    }

    private static Buffer render(BuiltinEditor editor, ShellThemes themes) {
        Buffer buffer = Buffer.empty(AREA);
        DefaultRenderContext context = context(themes);
        editor.element().render(Frame.forTesting(buffer), AREA, context);
        return buffer;
    }

    private static Style roleStyle(ShellThemes themes) {
        return EditorElement.roleStyle(context(themes), ROLE);
    }

    private static DefaultRenderContext context(ShellThemes themes) {
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        context.focusManager().setFocus(BuiltinEditor.TEXT_AREA_ID);
        return context;
    }
}
