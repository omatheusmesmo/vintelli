package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.AnsiColor;
import dev.tamboui.style.Color;
import dev.tamboui.style.ColorConverter;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorMarkerBarTest {

    private static final Rect AREA = new Rect(0, 0, 60, 22);
    private static final int BAR_X = AREA.right() - 1;
    private static final int BODY_ROWS = AREA.height() - 1;
    private static final String MARK = "■";
    private static final Color ERROR = Color.ansi(AnsiColor.BRIGHT_RED);
    private static final Color WARNING = Color.ansi(AnsiColor.BRIGHT_YELLOW);
    private static final Color CHANGE = Color.ansi(AnsiColor.BRIGHT_BLUE);

    @TempDir Path project;
    private final ShellThemes themes = new ShellThemes("dark", (Path) null);
    private String previousIconMode;

    @BeforeEach
    void pinUnicodeIcons() {
        previousIconMode = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "nerd");
    }

    @AfterEach
    void restoreIconMode() {
        if (previousIconMode == null) {
            System.clearProperty(IconMode.CONFIG_PROPERTY);
        } else {
            System.setProperty(IconMode.CONFIG_PROPERTY, previousIconMode);
        }
    }

    @Test
    void anErrorNearTheEndOfALongFileShowsARedCellNearTheBottomAndAClickLandsOnIt() throws IOException {
        BuiltinEditor editor = open("Long.txt", lines(1000));
        editor.setDiagnostics(List.of(problem("Long.txt", 899, Severity.ERROR)));

        Buffer buffer = render(editor, AREA);

        List<Integer> marked = markedCells(buffer);
        assertEquals(List.of(18), marked, "the error on line 900 shows one cell, in the bottom third of the bar");
        assertEquals(ERROR, buffer.get(BAR_X, 18).style().fg().orElse(null));

        editor.handlePointer(MouseEvent.press(MouseButton.LEFT, BAR_X, 18));

        assertEquals(899, editor.document().row(), "the click puts the caret on the error line");
        Buffer after = render(editor, AREA);
        int visibleFirst = editor.document().scrollRow();
        assertTrue(visibleFirst <= 899 && 899 < visibleFirst + BODY_ROWS, "the error line is scrolled into view");
        assertTrue(after.get(BAR_X, 18).style().bg().isPresent(), "the clicked cell sits under the thumb");
    }

    @Test
    void aClickOnACellJumpsToItsFirstMarkerOfTheShownKindAtTheDiagnosticColumn() throws IOException {
        BuiltinEditor editor = open("Jump.txt", lines(1000));
        editor.setDiagnostics(List.of(
                new Diagnostic(project.resolve("Jump.txt").toRealPath(), 905, 3, 905, 6, Severity.ERROR, "t", "later"),
                new Diagnostic(project.resolve("Jump.txt").toRealPath(), 900, 2, 900, 5, Severity.ERROR, "t", "first"),
                problem("Jump.txt", 895, Severity.WARNING)));
        render(editor, AREA);

        editor.handlePointer(MouseEvent.press(MouseButton.LEFT, BAR_X, 18));

        assertEquals(900, editor.document().row());
        assertEquals(2, editor.document().column());
    }

    @Test
    void theThumbFollowsTheViewportWhileScrolling() throws IOException {
        BuiltinEditor editor = open("Scroll.txt", lines(1000));
        Color thumb = ColorConverter.INSTANCE.convert(editor.palette().selection()).orElseThrow();

        assertEquals(List.of(0), thumbCells(render(editor, AREA), thumb));

        for (int step = 0; step < 100; step++) {
            editor.handlePointer(MouseEvent.scrollDown(10, 5));
        }
        List<Integer> afterWheel = thumbCells(render(editor, AREA), thumb);
        assertEquals(1, afterWheel.size());
        assertEquals(editor.document().scrollRow() / 48, afterWheel.getFirst());
        assertTrue(afterWheel.getFirst() > 5, "the thumb moved down with the text");
    }

    @Test
    void draggingTheThumbScrollsTheText() throws IOException {
        BuiltinEditor editor = open("Drag.txt", lines(1000));
        render(editor, AREA);
        int caretRow = editor.document().row();

        editor.handlePointer(MouseEvent.press(MouseButton.LEFT, BAR_X, 10));
        int afterPress = editor.document().scrollRow();
        editor.handlePointer(MouseEvent.drag(MouseButton.LEFT, BAR_X, 16));
        int afterDrag = editor.document().scrollRow();
        editor.handlePointer(MouseEvent.release(MouseButton.LEFT, BAR_X, 16));
        editor.handlePointer(MouseEvent.drag(MouseButton.LEFT, BAR_X, 2));

        assertTrue(afterPress > 400 && afterPress < 520, "a press on an empty cell centres the viewport there: " + afterPress);
        assertTrue(afterDrag > afterPress, "dragging down scrolls further down");
        assertEquals(afterDrag, editor.document().scrollRow(), "a drag after the release does nothing");
        assertEquals(caretRow, editor.document().row(), "scrolling by the bar leaves the caret alone");
    }

    @Test
    void breakpointsAndChangedLinesHaveTheirOwnColoursAndAnErrorOutranksThem() throws IOException {
        BuiltinEditor editor = open("Kinds.txt", lines(12));
        editor.toggleBreakpoint(8);
        editor.toggleBreakpoint(2);
        editor.vcsStatus(Map.of(), Map.of("Kinds.txt", Set.of(5, 2)));
        editor.setDiagnostics(List.of(problem("Kinds.txt", 2, Severity.ERROR), problem("Kinds.txt", 10, Severity.WARNING)));

        Buffer buffer = render(editor, AREA);

        assertEquals(CHANGE, buffer.get(BAR_X, 5).style().fg().orElse(null), "a changed line is blue");
        Color breakpoint = buffer.get(BAR_X, 8).style().fg().orElse(null);
        assertNotEquals(null, breakpoint);
        assertNotEquals(CHANGE, breakpoint, "a breakpoint has a colour of its own");
        assertEquals(EditorElement.roleStyle(context(), "editor-breakpoint-verified").fg().orElse(null), breakpoint);
        assertEquals(ERROR, buffer.get(BAR_X, 2).style().fg().orElse(null), "the error wins over a breakpoint and a change");
        assertEquals(WARNING, buffer.get(BAR_X, 10).style().fg().orElse(null));
        assertEquals(List.of(2, 5, 8, 10), markedCells(buffer));
    }

    @Test
    void theBarIsAbsentBelowTheWidthThresholdAndPresentAtIt() throws IOException {
        BuiltinEditor editor = open("Narrow.txt", lines(1000));
        editor.setDiagnostics(List.of(problem("Narrow.txt", 899, Severity.ERROR)));
        Rect narrow = new Rect(0, 0, ErrorStripe.MIN_EDITOR_WIDTH - 1, AREA.height());
        Rect wide = new Rect(0, 0, ErrorStripe.MIN_EDITOR_WIDTH, AREA.height());

        assertEquals(List.of(), markedCells(render(editor, narrow), narrow.right() - 1));
        editor.handlePointer(MouseEvent.press(MouseButton.LEFT, narrow.right() - 1, 18));
        assertEquals(18, editor.document().row(), "the last column is text when the editor is narrow");

        assertEquals(List.of(18), markedCells(render(editor, wide), wide.right() - 1));
        editor.handlePointer(MouseEvent.press(MouseButton.LEFT, wide.right() - 1, 18));
        assertEquals(899, editor.document().row(), "the last column is the bar at the threshold");
    }

    @Test
    void theHeaderSummarisesTheProblemsOfTheActiveFile() throws IOException {
        BuiltinEditor editor = open("Header.txt", lines(10));
        assertEquals("", problemSummary(editor), "a file nobody analysed shows no summary");

        editor.setDiagnostics(List.of(problem("Header.txt", 1, Severity.ERROR), problem("Header.txt", 2, Severity.ERROR),
                problem("Header.txt", 3, Severity.WARNING)));
        assertEquals("✖ 2 ▲ 1", problemSummary(editor));

        editor.setDiagnostics(List.of());
        assertEquals("✓", problemSummary(editor), "an analysed clean file shows a check");

        editor.diagnosticsUnavailable();
        assertEquals("", problemSummary(editor), "a provider that went away leaves no check behind");
    }

    private String problemSummary(BuiltinEditor editor) {
        Rect tabs = new Rect(0, 0, 80, 2);
        Buffer buffer = Buffer.empty(tabs);
        editor.renderTabBar(Frame.forTesting(buffer), tabs, context(), false, " ");
        String row = IntStream.range(0, tabs.width()).mapToObj(x -> buffer.get(x, 0).symbol())
                .collect(Collectors.joining());
        int name = row.indexOf("Header.txt");
        return row.substring(name + "Header.txt".length()).strip();
    }

    private static List<Integer> markedCells(Buffer buffer) {
        return markedCells(buffer, BAR_X);
    }

    private static List<Integer> markedCells(Buffer buffer, int x) {
        return IntStream.range(0, buffer.area().height()).filter(y -> MARK.equals(buffer.get(x, y).symbol()))
                .boxed().toList();
    }

    private static List<Integer> thumbCells(Buffer buffer, Color thumb) {
        return IntStream.range(0, BODY_ROWS).filter(y -> thumb.equals(buffer.get(BAR_X, y).style().bg().orElse(null)))
                .boxed().toList();
    }

    private static String lines(int count) {
        return IntStream.range(0, count).mapToObj(row -> "line " + row).collect(Collectors.joining("\n", "", "\n"));
    }

    private BuiltinEditor open(String name, String text) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, text);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, 0));
        return editor;
    }

    private Diagnostic problem(String name, int line, Severity severity) throws IOException {
        return new Diagnostic(project.resolve(name).toRealPath(), line, 0, line, 1, severity, "test", "problem");
    }

    private DefaultRenderContext context() {
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        return context;
    }

    private Buffer render(BuiltinEditor editor, Rect area) {
        Buffer buffer = Buffer.empty(area);
        DefaultRenderContext context = context();
        context.focusManager().setFocus(BuiltinEditor.TEXT_AREA_ID);
        editor.element().render(Frame.forTesting(buffer), area, context);
        return buffer;
    }
}
