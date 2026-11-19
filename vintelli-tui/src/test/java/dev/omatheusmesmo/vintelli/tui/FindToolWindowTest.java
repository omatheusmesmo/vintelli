package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.navigation.NavigationTarget;
import dev.omatheusmesmo.vintelli.tui.navigation.UsageSearch;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindToolWindowTest {

    private static final Rect AREA = new Rect(0, 0, 80, 8);

    private final ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    private final AtomicLong clock = new AtomicLong();
    private final FindToolWindow window = new FindToolWindow(() -> themes, clock::get);
    private final List<String> opened = new ArrayList<>();

    @Test
    void theHeaderNamesTheSymbolTheFileCountAndTheUsageCount() {
        window.show(search());

        String screen = render();

        assertTrue(screen.startsWith("Usages of helper in 2 files (3)"), screen);
    }

    @Test
    void usagesAreGroupedUnderTheirFileWithTheCountAndShowTheLinePreview() {
        window.show(search());

        String screen = render();

        assertTrue(screen.contains("Caller.java  src/app (2)"), screen);
        assertTrue(screen.contains("Util.java  src (1)"), screen);
        assertTrue(screen.contains("2:10") && screen.contains("int total = helper(1);"), screen);
        assertTrue(screen.indexOf("Caller.java") < screen.indexOf("int total = helper(1);")
                && screen.indexOf("int total = helper(1);") < screen.indexOf("Util.java"), screen);
    }

    @Test
    void theSymbolOfAPreviewRowIsHighlightedAndTheRestOfTheLineIsNot() {
        window.show(search());
        Buffer buffer = Buffer.empty(AREA);
        window.render(Frame.forTesting(buffer), AREA);

        int row = 2;
        int lead = "  ".length() + "  ".length() + 8 + "  ".length();
        String text = "int total = helper(1);";
        int match = lead + text.indexOf("helper");

        assertEquals("h", buffer.get(match, row).symbol());
        assertNotEquals(buffer.get(match, row).style(), buffer.get(lead, row).style());
        assertEquals(buffer.get(lead, row).style(), buffer.get(match + "helper".length(), row).style());
    }

    @Test
    void beforeAnySearchTheWindowTellsHowToStartOne() {
        assertTrue(render().contains("press Alt+F7"), render());
        assertFalse(window.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void upAndDownMoveTheSelectionAndEnterOpensItKeepingTheFocusInTheList() {
        window.show(search());
        window.setOpener((usage, focusEditor) -> opened.add(usage.file().getFileName() + ":" + usage.line()
                + ":" + focusEditor));

        assertEquals(1, window.selectedTarget().line(), "the first usage is selected, not the file row");
        assertTrue(window.handleKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertTrue(window.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));

        assertEquals(List.of("Caller.java:6:false"), opened);
        assertTrue(render().contains("> "), "the selection is marked");
    }

    @Test
    void f4OpensTheSelectionAndAsksForTheEditorFocus() {
        window.show(search());
        window.setOpener((usage, focusEditor) -> opened.add(usage.file().getFileName() + ":" + focusEditor));

        assertTrue(window.handleKey(KeyEvent.ofKey(KeyCode.F4)));

        assertEquals(List.of("Caller.java:true"), opened);
    }

    @Test
    void theNavigationKeysAreTakenBeforeTheShellBindingsOfEnterAndF4() {
        assertTrue(window.shouldHandleKeyBeforeShell(KeyEvent.ofKey(KeyCode.ENTER)));
        assertTrue(window.shouldHandleKeyBeforeShell(KeyEvent.ofKey(KeyCode.F4)));
        assertFalse(window.shouldHandleKeyBeforeShell(KeyEvent.ofChar('a', KeyModifiers.NONE)));
    }

    @Test
    void aFileRowOpensItsFirstUsage() {
        window.show(search());
        window.setOpener((usage, focusEditor) -> opened.add(usage.file().getFileName() + ":" + usage.line()));
        window.handleKey(KeyEvent.ofKey(KeyCode.UP));

        window.handleKey(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(List.of("Caller.java:1"), opened);
    }

    @Test
    void aSecondClickOnARowOpensItAndASingleClickOnlySelectsIt() {
        window.show(search());
        window.setOpener((usage, focusEditor) -> opened.add(usage.file().getFileName() + ":" + usage.line()));
        render();

        assertTrue(window.handleMouse(click(5, 4)));
        assertTrue(opened.isEmpty());
        clock.addAndGet(1_000_000L);
        assertTrue(window.handleMouse(click(5, 4)));

        assertEquals(List.of("Util.java:0"), opened);
    }

    @Test
    void aNewSearchReplacesTheLastOne() {
        window.show(search());

        window.show(new UsageSearch("run", List.of(usage("Main.java", "", 3, 4, "run();"))));

        String screen = render();
        assertTrue(screen.startsWith("Usages of run in 1 file (1)"), screen);
        assertFalse(screen.contains("Caller.java"), screen);
    }

    @Test
    void theWindowHasNoDirectChordAndStartsHidden() {
        assertEquals(ToolWindow.NO_SHORTCUT, window.shortcut());
        assertFalse(window.visibleByDefault());
        assertEquals(ToolWindow.Anchor.BOTTOM, window.anchor());
    }

    private String render() {
        Buffer buffer = Buffer.empty(AREA);
        window.render(Frame.forTesting(buffer), AREA);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < AREA.height(); y++) {
            for (int x = 0; x < AREA.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static MouseEvent click(int x, int y) {
        return new MouseEvent(MouseEventKind.PRESS, MouseButton.LEFT, x, y, KeyModifiers.NONE);
    }

    private static UsageSearch search() {
        return new UsageSearch("helper", List.of(
                usage("Caller.java", "src/app", 1, 9, "int total = helper(1);"),
                usage("Caller.java", "src/app", 6, 0, "helper(2);"),
                usage("Util.java", "src", 0, 5, "void helper() {}")));
    }

    private static NavigationTarget usage(String name, String directory, int line, int column, String preview) {
        int start = preview.indexOf(name.startsWith("Main") ? "run" : "helper");
        return new NavigationTarget(Path.of("/project").resolve(directory).resolve(name), name, directory, line,
                column, preview, start, start < 0 ? -1 : name.startsWith("Main") ? 3 : 6);
    }
}
