package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class EditorQuickFixHintTest {

    private static final Rect AREA = new Rect(0, 0, 50, 8);
    private static final String HINT = "☼";
    private static final String HINT_ASCII = "*";
    private static final String PROBLEM = "!";
    private static final String TEXT = "line 0\nline 1\nline 2\nline 3\nline 4\nline 5\n";

    @TempDir Path project;
    private String previousIconMode;

    @BeforeEach
    void pinUnicodeIcons() {
        previousIconMode = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "nerd");
    }

    @AfterEach
    void restoreIconMode() {
        restore(previousIconMode);
    }

    @Test
    void errorOnTheCaretRowShowsTheHint() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Error.txt", TEXT, 2);
        editor.setDiagnostics(List.of(problem("Error.txt", 2, Severity.ERROR)));

        Buffer buffer = render(editor, themes);

        assertEquals(HINT, hintCell(buffer, 2), "the caret row with an error shows the hint");
        assertEquals(" ", hintCell(buffer, 0), "rows away from the caret keep their plain gutter");
    }

    @Test
    void warningOnTheCaretRowShowsTheHint() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Warning.txt", TEXT, 2);
        editor.setDiagnostics(List.of(problem("Warning.txt", 2, Severity.WARNING)));

        assertEquals(HINT, hintCell(render(editor, themes), 2));
    }

    @Test
    void rowWithoutDiagnosticShowsNoHint() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Plain.txt", TEXT, 2);
        editor.setDiagnostics(List.of(problem("Plain.txt", 4, Severity.ERROR)));

        Buffer buffer = render(editor, themes);

        assertNotEquals(HINT, hintCell(buffer, 2), "the caret row without a diagnostic shows no hint");
        assertEquals(PROBLEM, hintCell(buffer, 4), "the diagnostic row away from the caret keeps its problem marker");
    }

    @Test
    void infoOnlyDiagnosticOnTheCaretRowShowsNoHint() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Info.txt", TEXT, 2);
        editor.setDiagnostics(List.of(problem("Info.txt", 2, Severity.INFORMATION),
                problem("Info.txt", 2, Severity.HINT)));

        Buffer buffer = render(editor, themes);

        assertNotEquals(HINT, hintCell(buffer, 2), "an information or hint diagnostic gets no quick fix hint");
        assertEquals(PROBLEM, hintCell(buffer, 2), "the caret row keeps the plain problem marker");
    }

    @Test
    void hintMovesWithTheCaretAndLeavesTheRowBehind() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Moves.txt", TEXT, 1);
        editor.setDiagnostics(List.of(problem("Moves.txt", 1, Severity.ERROR),
                problem("Moves.txt", 3, Severity.ERROR)));
        assertEquals(HINT, hintCell(render(editor, themes), 1));

        editor.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        editor.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        Buffer buffer = render(editor, themes);

        assertEquals(PROBLEM, hintCell(buffer, 1), "the row the caret left keeps its problem marker");
        assertEquals(HINT, hintCell(buffer, 3), "the caret row with a diagnostic shows the hint");
    }

    @Test
    void hintDisappearsWhenTheDiagnosticsClear() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = open(themes, "Clear.txt", TEXT, 2);
        editor.setDiagnostics(List.of(problem("Clear.txt", 2, Severity.ERROR)));
        assertEquals(HINT, hintCell(render(editor, themes), 2));

        editor.setDiagnostics(List.of());

        Buffer buffer = render(editor, themes);
        assertNotEquals(HINT, hintCell(buffer, 2), "the hint is gone once the diagnostics clear");
        assertEquals(" ", hintCell(buffer, 2), "the caret row falls back to the plain gutter");
    }

    @Test
    void asciiModeDrawsTheAsciiFallback() throws IOException {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            BuiltinEditor editor = open(themes, "Ascii.txt", TEXT, 2);
            editor.setDiagnostics(List.of(problem("Ascii.txt", 2, Severity.ERROR)));

            Buffer buffer = render(editor, themes);

            assertEquals(HINT_ASCII, hintCell(buffer, 2), "the ASCII face draws the ASCII lightbulb");
            assertTrue(hintCell(buffer, 2).chars().allMatch(c -> c < 128), "no non-ASCII glyph in ASCII mode");
        } finally {
            restore(previous);
        }
    }

    @Test
    void readOnlyFileShowsNoHint() throws IOException {
        Path file = project.resolve("ReadOnly.txt");
        Files.writeString(file, TEXT);
        file.toFile().setWritable(false);
        try {
            assumeFalse(Files.isWritable(file), "this platform still lets the owner write a read-only file");
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, 2));
            editor.setDiagnostics(List.of(problem("ReadOnly.txt", 2, Severity.ERROR)));

            Buffer buffer = render(editor, themes);

            assertNotEquals(HINT, hintCell(buffer, 2), "a read-only file shows no quick fix hint");
            assertEquals(PROBLEM, hintCell(buffer, 2), "the problem marker still shows on the caret row");
        } finally {
            file.toFile().setWritable(true);
        }
    }

    @Test
    void hintRoleIsStyledInBothShippedThemes() throws IOException {
        for (String theme : List.of("dark", "darcula")) {
            ShellThemes themes = new ShellThemes(theme, (Path) null);
            BuiltinEditor editor = open(themes, theme + ".txt", TEXT, 2);
            editor.setDiagnostics(List.of(problem(theme + ".txt", 2, Severity.ERROR)));

            Buffer buffer = render(editor, themes);

            assertNotNull(buffer.get(0, 2).style().fg().orElse(null), "the hint has a foreground in " + theme);
            assertEquals(warningForeground(themes), buffer.get(0, 2).style().fg().orElse(null),
                    "the hint uses the warning color role in " + theme);
        }
    }

    private BuiltinEditor open(ShellThemes themes, String name, String text, int row) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, text);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, row));
        return editor;
    }

    private Diagnostic problem(String name, int line, Severity severity) throws IOException {
        return new Diagnostic(project.resolve(name).toRealPath(), line, 0, line, 1, severity, "test", "problem");
    }

    private static Buffer render(BuiltinEditor editor, ShellThemes themes) {
        Buffer buffer = Buffer.empty(AREA);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        context.focusManager().setFocus(BuiltinEditor.TEXT_AREA_ID);
        editor.element().render(Frame.forTesting(buffer), AREA, context);
        return buffer;
    }

    private static String hintCell(Buffer buffer, int row) {
        return buffer.get(AREA.x(), AREA.y() + row).symbol();
    }

    private static dev.tamboui.style.Color warningForeground(ShellThemes themes) {
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        return EditorElement.roleStyle(context, "problem-warning").fg().orElse(null);
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(IconMode.CONFIG_PROPERTY);
        } else {
            System.setProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }
}
