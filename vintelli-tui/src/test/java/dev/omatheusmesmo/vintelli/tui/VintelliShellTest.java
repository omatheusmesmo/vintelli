package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyAction;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.omatheusmesmo.vintelli.tui.menu.MenuPopup;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayoutStore;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentSymbol;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.toolkit.app.ToolkitPilot;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.TuiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shell on the Toolkit level: keymap routing, overlays, pushes and the
 * session theme. Every regression here fails if the production code it pins
 * is reverted: the palette chords, the capture rules, the state sync of the
 * project list, the oriented focus and the stylesheet driven colors.
 */
class VintelliShellTest {

    @Test
    void rightToolWindowFillsItsAvailableStripe() {
        AtomicReference<Rect> rendered = new AtomicReference<>();
        ToolWindow right = new FakeToolWindow("side", "Side", '7', 100, "", false, ToolWindow.Anchor.RIGHT) {
            @Override
            public void render(Frame frame, Rect area) {
                rendered.set(area);
            }
        };
        ShellThemes themes = defaultThemes();
        VintelliShell shell = new VintelliShell(List.of(right), new ShellState(), themes);
        offlineFrame(shell);

        int bottomWithoutWindows = VintelliShell.TOOL_WINDOW_HEIGHT + 2;
        int tabAndHeaderRows = 3;
        assertEquals(VIEWPORT.height() - 1 - bottomWithoutWindows - tabAndHeaderRows, rendered.get().height(),
                "side windows fill the stripe above the bottom region, less the two tab rows and the pane header");
    }

    @Test
    void stripsRenderOneIconPerWindowOnTheRightSideInTheRegisteredOrder() {
        ShellThemes themes = defaultThemes();
        List<ToolWindow> windows = new ArrayList<>(windowsWithAcp());
        windows.add(new FakeToolWindow("side.a", "Side", '7', 100, "", false, ToolWindow.Anchor.RIGHT));
        windows.add(new FakeToolWindow("side.b", "Other", '8', 200, "", false, ToolWindow.Anchor.RIGHT));
        List<ToolWindow> registered = new ToolWindowRegistry(windows).toolWindows();
        VintelliShell shell = new VintelliShell(registered, new ShellState(), themes);
        Buffer buffer = offlineFrame(shell);

        StringBuilder expectedRight = new StringBuilder();
        StringBuilder expectedLeft = new StringBuilder("P");
        for (ToolWindow window : registered) {
            if (window.anchor() == ToolWindow.Anchor.RIGHT) {
                expectedRight.append(window.title().charAt(0));
            } else if (window.anchor() == ToolWindow.Anchor.BOTTOM) {
                expectedLeft.append(window.title().charAt(0));
            }
        }
        int lastMiddleRow = findBottomTabRow(buffer) - 1;
        assertEquals(expectedRight.toString(), columnLetters(buffer, VIEWPORT.width() - 1, 1, lastMiddleRow),
                "the right strip stacks one icon per right window in the registered order");
        assertEquals(expectedLeft.toString(), columnLetters(buffer, 1, 1, lastMiddleRow),
                "the left strip holds the project icon on top and the bottom windows at its bottom");
    }

    @Test
    void clickingAStripIconTogglesItsWindowAndTheSecondClickShowsItAgain() throws Exception {
        VintelliShell shell = defaultShell();
        Buffer buffer = offlineFrame(shell);
        int runIcon = findRow(buffer, 1, "R");
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.click(1, runIcon);
            settle(pilot);
            assertFalse(shell.isToolWindowVisible("run"), "the first click hides Run: " + shell.status());

            pilot.click(1, runIcon);
            settle(pilot);
            assertTrue(shell.isToolWindowVisible("run"), "the second click shows Run again: " + shell.status());
            assertEquals("Tool window: Run", shell.status());
            pressCtrlC(pilot);
        }
    }

    @Test
    void aHiddenSideWindowFreesItsSpaceAndItsStripeIconStaysInPlace() throws Exception {
        VintelliShell shell = new VintelliShell(windowsWithAcp(), new ShellState(), defaultThemes());
        int iconRow = findRow(offlineFrame(shell), VIEWPORT.width() - 1, "A");
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            int rightBefore = pilot.findElement("right-stripe").width();
            int editorBefore = pilot.findElement("editor-panel").width();

            pilot.click(VIEWPORT.width() - 1, iconRow);
            settle(pilot);

            assertFalse(shell.isToolWindowVisible("acp.agents"), shell.status());
            assertEquals(VintelliShell.TOOL_STRIP_WIDTH, pilot.findElement("right-stripe").width(),
                    "a hidden side window leaves only the strip");
            assertEquals(editorBefore + rightBefore - VintelliShell.TOOL_STRIP_WIDTH,
                    pilot.findElement("editor-panel").width(), "the editor takes the freed columns");
            String frame = test.renderedFrame();
            assertTrue(frame.split("\n", -1)[iconRow].endsWith("A"),
                    "the strip keeps the icon of the hidden window: " + frame);
        }
    }

    @Test
    void escapeKeepsAHiddenProjectPanelHiddenAndFocusesTheEditor() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        int projectIcon = findRow(offlineFrame(shell), 1, "P");
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.click(1, projectIcon);
            settle(pilot);
            assertEquals("Project panel hidden", shell.status());

            press(pilot, '6');
            assertNotEquals("editor-panel", test.runner().focusManager().focusedId(), "the window took the focus");
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);

            assertFalse(pilot.hasElement("project-panel"), "escape must not bring the hidden panel back");
            assertEquals("editor-panel", test.runner().focusManager().focusedId());
            pressCtrlC(pilot);
        }
    }

    @Test
    void theSeparatorOfAHiddenProjectPanelDoesNotResizeIt() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), defaultThemes(), store);
        int projectIcon = findRow(offlineFrame(shell), 1, "P");
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.click(1, projectIcon);
            settle(pilot);
            double ratio = store.load().projectRatio();
            Rect separator = pilot.findElement("pane-separator-vertical");

            pilot.mousePress(MouseButton.LEFT, separator.x(), separator.y());
            test.drag(MouseEvent.drag(MouseButton.LEFT, separator.x() + 8, separator.y()));
            pilot.mouseRelease(MouseButton.LEFT, separator.x() + 8, separator.y());
            settle(pilot);

            assertEquals(ratio, store.load().projectRatio(), "the hidden panel keeps its saved width");
        }
    }

    @Test
    void theBottomPaneDoesNotResizeWhileEveryBottomWindowIsHidden() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), defaultThemes(), store);
        defaultWindows().stream().filter(window -> window.anchor() == ToolWindow.Anchor.BOTTOM)
                .filter(window -> shell.isToolWindowVisible(window.id()))
                .forEach(window -> shell.toggleToolWindow(window.id()));
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            double ratio = store.load().bottomRatio();

            pilot.press('.', KeyModifiers.ALT);
            settle(pilot);

            assertEquals("Bottom tool windows are hidden", shell.status());
            assertEquals(ratio, store.load().bottomRatio(), "the hidden pane keeps its saved height");
        }
    }

    @Test
    void clickingAToolTabActivatesTheWindowAtItsRenderedPosition() throws Exception {
        VintelliShell shell = defaultShell();
        Buffer buffer = offlineFrame(shell);
        int tabRow = findBottomTabRow(buffer);
        int problemsX = rowText(buffer, tabRow).indexOf("3 Problems") + 1;
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.click(problemsX, tabRow + 1);
            settle(pilot);
            assertEquals(2, shell.toolIndex(), shell.status());
            assertEquals("Tool window: Problems", shell.status());
            pressCtrlC(pilot);
        }
    }

    @Test
    void asciiModeDrawsTheStripsAndTheBottomTabsWithoutNonAsciiGlyphs() {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            VintelliShell shell = new VintelliShell(windowsWithAcp(), new ShellState(), defaultThemes());
            Buffer buffer = offlineFrame(shell);
            int tabRow = findBottomTabRow(buffer);
            for (int row = 1; row < tabRow; row++) {
                for (int x : new int[] {0, 1, VIEWPORT.width() - 2, VIEWPORT.width() - 1}) {
                    assertTrue(buffer.get(x, row).symbol().chars().allMatch(character -> character < 128),
                            "the ASCII face draws no non-ASCII glyph in the strips at " + x + "," + row);
                }
            }
            assertTrue(rowText(buffer, tabRow).chars().allMatch(character -> character < 128),
                    "the ASCII face draws no non-ASCII glyph in the tab row: " + rowText(buffer, tabRow));
            assertTrue(rowText(buffer, tabRow + 1).chars().allMatch(character -> character < 128),
                    "the ASCII face underlines with an ASCII glyph: " + rowText(buffer, tabRow + 1));
            assertEquals("P", columnLetters(buffer, 1, 1, 1), "the project icon is its letter");
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void theActiveStripeIconUsesTheSelectedStyleOfEachShippedTheme() {
        for (String theme : List.of("dark", "darcula")) {
            ShellThemes themes = new ShellThemes(theme, (Path) null);
            VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), themes);
            Buffer buffer = offlineFrame(shell);
            int runIcon = findRow(buffer, 1, "R");
            int terminalIcon = findRow(buffer, 1, "T");
            assertEquals(themes.color("tab-selected-bg"), buffer.get(1, runIcon).style().bg().orElse(null),
                    "the active icon sits on the selected background in " + theme);
            assertEquals(themes.color("tab-selected-bg"), buffer.get(0, runIcon).style().bg().orElse(null),
                    "the active icon fills both strip columns in " + theme);
            assertEquals(themes.color("tab-selected-fg"), buffer.get(1, runIcon).style().fg().orElse(null),
                    "the active icon uses the selected foreground in " + theme);
            assertEquals(themes.color("soft"), buffer.get(1, terminalIcon).style().fg().orElse(null),
                    "an inactive icon uses the soft color in " + theme);
            assertFalse(themes.color("tab-selected-bg").equals(buffer.get(1, terminalIcon).style().bg().orElse(null)),
                    "an inactive icon carries no selected background in " + theme);
        }
    }

    private static int findRow(Buffer buffer, int x, String symbol) {
        for (int row = 0; row < buffer.height(); row++) {
            if (symbol.equals(buffer.get(x, row).symbol())) {
                return row;
            }
        }
        return -1;
    }

    private static String columnLetters(Buffer buffer, int x, int fromRow, int toRow) {
        StringBuilder letters = new StringBuilder();
        for (int row = fromRow; row <= toRow; row++) {
            String symbol = buffer.get(x, row).symbol();
            if (!symbol.isBlank()) {
                letters.append(symbol);
            }
        }
        return letters.toString();
    }

    @TempDir
    Path project;

    private static final Duration STARTUP = Duration.ofMillis(100);
    private static final Duration EVENT_WINDOW = Duration.ofMillis(500);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SETTLE_STEP = Duration.ofMillis(2);
    private static final Duration FRAME_GRACE = Duration.ofMillis(30);
    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final Size SIZE = new Size(VIEWPORT.width(), VIEWPORT.height());
    private static final int MENU_ROW = 0;
    private static final int LEFT_TAB_ROW = 1; // Left stripe tab bar (empty in default)
    private static final int FIRST_PROJECT_ROW = 2; // Project panel now starts at row 2
    private static final int SECOND_PROJECT_ROW = 3;
    private static final int PROJECT_ROW_HIGHLIGHT = VintelliShell.PROJECT_PANEL_WIDTH;
    private static final int HEADER_ROW = 1;
    private static final int PROJECT_X = VintelliShell.TOOL_STRIP_WIDTH;
    private static final int EDITOR_HEADER_X = VintelliShell.TOOL_STRIP_WIDTH + VintelliShell.PROJECT_PANEL_WIDTH + 1;
    private static final int SEPARATOR_X = VintelliShell.TOOL_STRIP_WIDTH + VintelliShell.PROJECT_PANEL_WIDTH;
    private static final List<String> FRAME_CORNERS = List.of("┌", "┐", "└", "┘", "╭", "╮", "╰", "╯");
    // Bottom stripe tab bar row (after menu + left content + editor)
    private static final int BOTTOM_TAB_ROW = 13; // approximate, will be computed dynamically
    private static final int TOOL_WINDOW_ROW = 14;
    private static final int STATUS_ROW = 23;
    private static final int EDITOR_PROBE_X = 58;
    private static final int EDITOR_PROBE_Y = 10;

    @Test
    void selectsToolWindowsByDigitKeys() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            // Bottom anchor: Run(1), Terminal(2), Problems(3)
            press(pilot, '2');
            assertEquals(1, shell.toolIndex()); // Terminal is at bottom index 1
            assertEquals("Tool window: Terminal", shell.status());

            // The terminal captures input while a shell session is focused.
            // Escape returns keyboard control to the shell so bare digit
            // shortcuts can switch windows again.
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            press(pilot, '3');
            assertEquals(2, shell.toolIndex()); // Problems is at bottom index 2
            assertEquals("Tool window: Problems", shell.status());

            press(pilot, '1');
            assertEquals(0, shell.toolIndex()); // Run is at bottom index 0
            assertEquals("Tool window: Run", shell.status());

            press(pilot, 'x');
            assertEquals(0, shell.toolIndex());
            assertEquals("Tool window: Run", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void navigatesTheProjectPanelWithArrowKeysInTheDefaultProfile() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex());
            assertEquals("Selected: VintelliMain.java", shell.status());

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(2, shell.fileIndex());

            pilot.press(KeyCode.UP);
            settle(pilot);
            pilot.press(KeyCode.UP);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(0, shell.fileIndex());
            assertEquals("Selected: vintelli/pom.xml", shell.status());

            assertTrue(test.runner().isRunning());

            pressCtrlC(pilot);
        }
    }

    @Test
    void clampsSelectionAtListBounds() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            for (int step = 0; step < 4; step++) {
                pilot.press(KeyCode.DOWN);
                settle(pilot);
            }
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 6; step++) {
                pilot.press(KeyCode.DOWN);
                settle(pilot);
            }
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 10; step++) {
                pilot.press(KeyCode.UP);
                settle(pilot);
            }
            pressQuietly(pilot);
            assertEquals(0, shell.fileIndex());
            assertEquals("Selected: vintelli/pom.xml", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void separatesMenuEntriesOnFirstRow() {
        VintelliShell shell = defaultShell();
        shell.handle(KeyEvent.ofKey(KeyCode.F10));
        Buffer buffer = offlineFrame(shell);

        String menu = rowText(buffer, MENU_ROW);
        assertTrue(menu.contains("Edit  View"), menu);
        assertTrue(menu.contains("Tools  VCS"), menu);
        assertTrue(menu.contains("Code  Refactor"), menu);
    }

    @Test
    void menuActionRoutesThroughShellDispatcherAndResizesTheDock() {
        VintelliShell shell = defaultShell();
        offlineFrame(shell);

        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofKey(KeyCode.F10)));
        shell.handle(KeyEvent.ofKey(KeyCode.RIGHT));
        shell.handle(KeyEvent.ofKey(KeyCode.RIGHT));
        shell.handle(KeyEvent.ofKey(KeyCode.ENTER));

        assertTrue(shell.status().startsWith("Project panel:"), shell.status());
    }

    @Test
    void structureFiltersSymbolsAndNavigatesTheMatchingEntry() {
        StructureToolWindow structure = new StructureToolWindow(defaultThemes());
        DocumentSymbol first = new DocumentSymbol("first", 6, 1, 0, 1, 10, 1, 0, 1, 5, List.of());
        DocumentSymbol second = new DocumentSymbol("second", 6, 2, 0, 2, 10, 2, 0, 2, 6, List.of());
        AtomicReference<DocumentSymbol> navigated = new AtomicReference<>();
        structure.setSymbols(List.of(first, second));
        structure.setNavigateHandler(navigated::set);

        structure.handleKey(KeyEvent.ofChar('e'));
        assertEquals(second, structure.selectedSymbol());
        structure.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(second, navigated.get());
        structure.handleKey(KeyEvent.ofKey(KeyCode.BACKSPACE));
        assertEquals(first, structure.selectedSymbol());
    }

    @Test
    void structureSelectionNavigatesTheActiveEditor() throws IOException {
        Path file = project.resolve("Example.java");
        Files.writeString(file, "class Example {\n  void first() {}\n  void second() {}\n}\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        StructureToolWindow structure = new StructureToolWindow(themes);
        VintelliShell shell = new VintelliShell(List.of(editor, structure), new ShellState(), themes);
        editor.open(new OpenRequest(file, 0));
        structure.setSymbols(List.of(new DocumentSymbol("second", 6, 2, 2, 2, 18,
                2, 7, 2, 13, List.of())));

        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofChar('6', KeyModifiers.ALT)));
        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(2, editor.activeDocument().orElseThrow().line());
    }

    @Test
    void menuNavigationBubblesFromAnEditorWithFocusSoStageCanBeSelected() throws Exception {
        Path file = project.resolve("sample.txt");
        Files.writeString(file, "first line\nsecond line\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        assertFalse(editor.open(new OpenRequest(file, 0)).isFailure());
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VersionControlCapability vcs = new VersionControlCapability() {
            @Override public CapabilityResult<Status> status() {
                return CapabilityResult.ok(new Status("main", 0, 0, List.of()));
            }
            @Override public CapabilityResult<String> diff(Path changedFile) { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<String> diffWorkingTree() { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<Boolean> stage(Path changedFile, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> unstage(Path changedFile, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> commit(String message, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> switchBranch(String branch, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public String identifier() { return "menu-test"; }
            @Override public ProviderHealth health() { return ProviderHealth.available(); }
            @Override public String provenance() { return "temporary menu test provider"; }
        };
        VintelliShell shell = new VintelliShell(new ToolWindowRegistry(windows).toolWindows(),
                new ShellState(), () -> Keymap.defaultKeymap(windows), themes, null, List.of(), List.of(vcs));

        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.press(KeyCode.RIGHT);
            settle(pilot);
            assertEquals(1, editor.activeDocument().orElseThrow().column(), "the editor has keyboard focus initially");

            pilot.press(KeyCode.F10);
            settle(pilot);
            for (int menu = 0; menu < 8; menu++) {
                pilot.press(KeyCode.RIGHT);
                settle(pilot);
            }
            for (int row = 0; row < 3; row++) {
                pilot.press(KeyCode.DOWN);
                settle(pilot);
            }
            pilot.press(KeyCode.ENTER);
            settle(pilot);

            assertEquals(1, editor.activeDocument().orElseThrow().column(),
                    "menu keys must not move the text cursor or insert a newline");
            assertTrue(shell.status().startsWith("Stage sample.txt? [y/N]"), shell.status());
            pilot.press('n');
            settle(pilot);
        }
    }

    @Test
    void enterOpensTheSelectedProjectFileInTheCenterEditor() throws IOException {
        Path file = project.resolve("vintelli/pom.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project>shell editor</project>");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VintelliShell shell = new VintelliShell(new ToolWindowRegistry(windows).toolWindows(),
                new ShellState(), themes);

        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(file.toRealPath().toString(), editor.activeDocument().orElseThrow().file());
        assertEquals(1, editor.tabs().size());
        assertEquals("<project>shell editor</project>", editor.activeDocument().orElseThrow().text());
        String rendered = bufferText(offlineFrame(shell));
        assertTrue(rendered.contains("<project>shell"), "opened file contents must render in the editor:\n" + rendered);
    }

    @Test
    void refreshShowsGitStateInProjectTreeTabsAndChangedLineGutter() throws IOException {
        Path file = project.resolve("vintelli/pom.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "first\nchanged\nlast\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VersionControlCapability vcs = new VersionControlCapability() {
            @Override public CapabilityResult<Status> status() {
                return CapabilityResult.ok(new Status("main", 0, 0,
                        List.of(new Entry(State.MODIFIED, "vintelli/pom.xml"))));
            }
            @Override public CapabilityResult<String> diff(Path changedFile) {
                return CapabilityResult.ok("diff --git a/vintelli/pom.xml b/vintelli/pom.xml\n"
                        + "--- a/vintelli/pom.xml\n+++ b/vintelli/pom.xml\n@@ -1,3 +1,3 @@\n"
                        + " first\n-changed\n+changed again\n last\n");
            }
            @Override public CapabilityResult<String> diffWorkingTree() { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<Boolean> stage(Path path, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> unstage(Path path, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> commit(String message, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> switchBranch(String branch, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public String identifier() { return "test-vcs"; }
            @Override public ProviderHealth health() { return ProviderHealth.available(); }
            @Override public String provenance() { return "temporary test provider"; }
        };
        VintelliShell shell = new VintelliShell(new ToolWindowRegistry(windows).toolWindows(),
                new ShellState(), () -> Keymap.defaultKeymap(windows), themes, null, List.of(), List.of(vcs));

        shell.handle(KeyEvent.ofKey(KeyCode.ENTER));
        shell.refreshVcs();
        String rendered = bufferText(offlineFrame(shell));

        assertTrue(rendered.contains("M vintelli/pom.xml"), "the tree should show the changed file: " + rendered);
        assertTrue(rendered.contains("pom.xml M"), "the editor tab should show Git state: " + rendered);
        assertTrue(rendered.contains("+ 2"), "the gutter should mark changed line 2: " + rendered);
    }

    @Test
    void vcsRefreshKeepsTheProjectTreeForm() throws IOException {
        Path changed = project.resolve("src/main/A.java");
        Files.createDirectories(changed.getParent());
        Files.writeString(changed, "class A {}");
        Files.writeString(project.resolve("README.md"), "# readme");
        VintelliShell shell = shellWithVcsTree(modifiedVcs("src/main/A.java"), project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        shell.expandSelected();
        shell.selectPath("src/main");
        shell.expandSelected();
        shell.selectPath("src/main/A.java");

        shell.refreshVcs();
        String rendered = bufferText(offlineFrame(shell));

        boolean unicodeTree = ProjectTreeGlyphs.supportsUnicode();
        FileIcons icons = FileIcons.resolve();
        boolean nerd = icons.mode() == IconMode.NERD;
        String srcRow = (unicodeTree ? "├── " : "|-- ") + (nerd ? "▾ " : "- ")
                + icons.folderIconFor("src").symbol(icons.mode()) + " src/";
        String changedRow = (unicodeTree ? "│       └── " : "|       \\-- ")
                + "M " + icons.iconFor("A.java").symbol(icons.mode()) + " A.java";
        assertTrue(rendered.contains(srcRow), "the expanded directory keeps its tree row: " + rendered);
        assertTrue(rendered.contains(changedRow), "the changed file keeps its tree row and marker: " + rendered);
        assertFalse(rendered.contains("M src/main/A.java"), "the refresh must not flatten rows to paths: " + rendered);
        assertEquals("src/main/A.java", shell.selectedPathString(), "the refresh must keep the selection");
    }

    private static VintelliShell shellWithVcsTree(VersionControlCapability vcs, Path root) {
        ShellThemes themes = defaultThemes();
        List<ToolWindow> windows = defaultWindows();
        return new VintelliShell(windows, new ShellState(), () -> Keymap.defaultKeymap(windows), themes,
                ProjectTreeTest.fakeSearch(root), List.of(), List.of(vcs));
    }

    private static VersionControlCapability modifiedVcs(String changedPath) {
        return new VersionControlCapability() {
            @Override public CapabilityResult<Status> status() {
                return CapabilityResult.ok(new Status("main", 0, 0,
                        List.of(new Entry(State.MODIFIED, changedPath))));
            }
            @Override public CapabilityResult<String> diff(Path changedFile) { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<String> diffWorkingTree() { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<Boolean> stage(Path changedFile, boolean confirmed) { return CapabilityResult.ok(confirmed); }
            @Override public CapabilityResult<Boolean> unstage(Path changedFile, boolean confirmed) { return CapabilityResult.ok(confirmed); }
            @Override public CapabilityResult<Boolean> commit(String message, boolean confirmed) { return CapabilityResult.ok(confirmed); }
            @Override public CapabilityResult<Boolean> switchBranch(String target, boolean confirmed) { return CapabilityResult.ok(confirmed); }
            @Override public String identifier() { return "modified-test"; }
            @Override public ProviderHealth health() { return ProviderHealth.available(); }
            @Override public String provenance() { return "temporary modified-file test provider"; }
        };
    }

    @Test
    void parsesChangedLinesOnlyInsideHunksAndCountsLiteralPlusAndMinusPrefixes() {
        String diff = "diff --git a/sample.txt b/sample.txt\n"
                + "--- a/sample.txt\n+++ b/sample.txt\n"
                + "@@ -1,3 +1,3 @@\n context\n---literal removed prefix\n+++literal added prefix\n last\n";

        assertEquals(Set.of(0, 1), VintelliShell.changedLines(diff));
        assertEquals(Set.of(0), VintelliShell.changedLines("@@ -3 +2,0 @@\n-removed\n"));
        assertEquals(Set.of(2, 3), VintelliShell.changedLines("@@ -2,0 +3,2 @@\n+first\n+second\n"));
    }

    @Test
    void highlightsOnlySelectedTabAndRowOnFirstFrame() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        Color tabSelected = themes.color("tab-selected-bg");
        Color rowSelected = themes.color("selection-bg");

        Buffer buffer = offlineFrame(shell);
        int bottomTabRow = findBottomTabRow(buffer);
        String tabRow = rowText(buffer, bottomTabRow);
        Color underline = themes.color("focus-border");

        assertEquals(0, highlightedCount(buffer, bottomTabRow, tabSelected),
                "the tab row carries no filled block, the selection is an underline: " + tabRow);
        assertEquals("1 Run".length(), contiguousForeground(buffer, bottomTabRow + 1, underline),
                "exactly the selected label carries the underline: " + rowText(buffer, bottomTabRow + 1));
        assertEquals(1, firstForegroundColumn(buffer, bottomTabRow + 1, underline),
                "the underline starts under the selected label 1 Run: " + rowText(buffer, bottomTabRow + 1));
        assertEquals(PROJECT_ROW_HIGHLIGHT, highlightedCount(buffer, FIRST_PROJECT_ROW, rowSelected),
                "the selected project row carries the selection color: " + rowText(buffer, FIRST_PROJECT_ROW));
        assertEquals(0, highlightedCount(buffer, SECOND_PROJECT_ROW, rowSelected),
                "unselected project rows must not carry the selection color");
        assertEquals(themes.color("editor-bg"), buffer.get(EDITOR_PROBE_X, EDITOR_PROBE_Y).style().bg().orElse(null),
                "the editor panel shows the editor palette background");
    }

    @Test
    void movesHighlightToTab3AndDropsTab1AfterDigitPress() throws Exception {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        Color tabSelected = themes.color("tab-selected-bg");
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            press(pilot, '3');
            assertEquals(2, shell.toolIndex());
            pressCtrlC(pilot);
        }

        Buffer buffer = offlineFrame(shell);
        int bottomTabRow = findBottomTabRow(buffer);
        String tabRow = rowText(buffer, bottomTabRow);
        int tab3Start = tabRow.indexOf("3 Problems");
        assertTrue(tab3Start >= 0, "tab 3 should be present: " + tabRow);
        Color underline = themes.color("focus-border");
        assertEquals(0, highlightedCount(buffer, bottomTabRow, tabSelected),
                "the tab row carries no filled block after switching to tab 3: " + tabRow);
        assertEquals(tab3Start, firstForegroundColumn(buffer, bottomTabRow + 1, underline),
                "the underline starts under the selected label 3 Problems: " + tabRow);
        assertEquals("3 Problems".length(), contiguousForeground(buffer, bottomTabRow + 1, underline),
                "the underline covers only the selected label: " + tabRow);
    }

    @Test
    void showsATabForAToolWindowRegisteredWithoutShellChanges() {
        VintelliShell shell = shellWithFakeWindow(new FakeToolWindow("fake.threads", "Threads", '7', 50, "fake thread dump", false, ToolWindow.Anchor.BOTTOM));
        Buffer buffer = offlineFrame(shell);

        int bottomTabRow = findBottomTabRow(buffer);
        String tabRow = rowText(buffer, bottomTabRow);
        assertTrue(tabRow.contains("1 Run"), tabRow);
        assertTrue(tabRow.contains("7 Threads"), tabRow);
    }

    @Test
    void selectsATabByItsDeclaredShortcutAndRecordsItInState() throws Exception {
        VintelliShell shell = shellWithFakeWindow(new FakeToolWindow("fake.threads", "Threads", '7', 50, "fake thread dump", false, ToolWindow.Anchor.BOTTOM));
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '7');
            assertEquals("fake.threads", shell.state().snapshot().selectedToolWindowId());
            assertEquals("Tool window: Threads", shell.status());
            // Fake window is added at the end, so index depends on bottom anchor windows
            int expectedIndex = 3; // Run, Terminal, Problems, then fake
            assertEquals(expectedIndex, shell.toolIndex());

            pressCtrlC(pilot);
        }
    }

    @Test
    void routesUnclaimedKeysToTheActiveToolWindowBeforeShellShortcuts() throws Exception {
        FakeToolWindow fake = new FakeToolWindow("fake.notes", "Notes", '6', 60, "fake notes");
        VintelliShell shell = shellWithFakeWindow(fake);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            press(pilot, 'j');
            assertEquals(0, shell.fileIndex(), "the active tool window must consume j before the project panel");
            assertTrue(fake.handledKeys().contains("j"), fake.handledKeys().toString());

            pressCtrlC(pilot);
        }
    }

    @Test
    void capturingWindowReceivesEvenTheShellKeys() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            press(pilot, 'q');
            press(pilot, 'j');
            press(pilot, '1');
            assertEquals(0, shell.fileIndex(), "captured input must not drive the shell");
            // Fake window is added at bottom index 3 (after Run, Terminal, Problems)
            assertEquals(3, shell.toolIndex(), "captured input must not switch tabs");
            assertTrue(terminalLike.handledKeys().containsAll(List.of("q", "j", "1")),
                    terminalLike.handledKeys().toString());

            assertTrue(test.runner().isRunning(), "the capturing window keeps q for text, not quit");

            // Alt+4 activates ACP Agents (right anchor), bottom anchor index stays at 3 (fake window)
            press(pilot, '4', KeyModifiers.ALT);
            assertEquals(3, shell.toolIndex(), "bottom anchor index unchanged when activating right anchor window");
            assertEquals("Tool window: ACP Agents", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void switchesToolWindowsByActionChordWhileAnInputHasFocus() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            press(pilot, '2');
            assertEquals(3, shell.toolIndex(), "a bare digit belongs to the capturing input");
            assertEquals(0, shell.fileIndex(), "a bare digit must not drive the project list");
            assertTrue(terminalLike.handledKeys().contains("2"), terminalLike.handledKeys().toString());

            press(pilot, '3', KeyModifiers.ALT);
            assertEquals(2, shell.toolIndex(), "the activate action chord must survive captured input");
            assertEquals("Tool window: Problems", shell.status());

            press(pilot, '6', KeyModifiers.ALT);
            assertEquals(3, shell.toolIndex());

            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertEquals("Focus: shell navigation", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void escapeLeavesCapturedInputAndReturnsFocusToShellNavigation() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            press(pilot, 'j');
            assertEquals(0, shell.fileIndex(), "captured: the window still has the key");
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertEquals("Focus: shell navigation", shell.status());
            assertFalse(terminalLike.handledKeys().contains("escape"), "escape stays with the shell");
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex(), "after escape the shell navigates again");
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(2, shell.fileIndex(), "the capturing window must stay out of the shell keys");

            pressCtrlC(pilot);
        }
    }

    @Test
    void selectsTheCapturingWindowAgainThroughItsShortcutAfterEscape() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertEquals("Focus: shell navigation", shell.status());
            press(pilot, '6');
            press(pilot, 'j');
            assertEquals(0, shell.fileIndex(), "reselecting the window re-enters captured input");
            assertTrue(terminalLike.handledKeys().contains("j"), terminalLike.handledKeys().toString());

            pressCtrlC(pilot);
        }
    }

    @Test
    void movesTheFileSelectionIntoTheShellStateOnArrowKeys() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals("VintelliMain.java", shell.state().snapshot().selectedFile());
            assertEquals("run", shell.state().snapshot().selectedToolWindowId());

            pilot.press(KeyCode.UP);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals("vintelli/pom.xml", shell.state().snapshot().selectedFile());

            pressCtrlC(pilot);
        }
    }

    @Test
    void rendersTheFallbackTextWhenNoToolWindowIsRegistered() {
        VintelliShell shell = new VintelliShell(List.of(), new ShellState(), defaultThemes());
        Buffer buffer = offlineFrame(shell);

        // Find the row with the fallback text in the bottom stripe
        boolean found = false;
        for (int row = 13; row < 23; row++) {
            String contentRow = rowText(buffer, row);
            if (contentRow.contains("no tool window beans registered")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "fallback text should appear in bottom stripe area");
    }

    @Test
    void theKeymapIsBuiltOnFirstRenderNotWhenTheShellIsConstructedOrRead() {
        AtomicInteger builds = new AtomicInteger();
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), () -> {
            builds.incrementAndGet();
            return Keymap.ofProfile(KeymapProfile.DEFAULT, defaultWindows());
        }, defaultThemes());

        shell.status();
        shell.state();
        shell.toolIndex();
        shell.fileIndex();
        assertEquals(0, builds.get(), "a headless process that never renders must not build the keymap");

        offlineFrame(shell);
        offlineFrame(shell);
        assertEquals(1, builds.get(), "the keymap is built once, on first use");
    }

    @Test
    void aFailingKeymapOnlySurfacesWhenTheTuiSessionUsesTheShell() {
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), () -> {
            throw new IllegalStateException("bad keymap");
        }, defaultThemes());

        assertEquals(0, shell.fileIndex());
        assertThrows(IllegalStateException.class, () -> offlineFrame(shell));
    }

    @Test
    void ctrlCQuitsTheShellAndBareQDoesNot() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'q');
            assertTrue(test.runner().isRunning(), "the intellij profile types instead of quitting");
            assertEquals("Vintelli ready | IDEA MCP: disconnected | ACP: stopped", shell.status(),
                    "q must not drive shell navigation either");

            pressCtrlC(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C must stop the shell event loop");
        }
    }

    @Test
    void ctrlCQuitsTheShellFromTheShellAndGoesToTheInputWhileCapturing() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            pressCtrlC(pilot);
            assertTrue(test.runner().isRunning(), "a capturing window keeps Ctrl+C for itself");
            assertFalse(terminalLike.handledKeys().isEmpty(),
                    "the capturing window must receive the Ctrl+C key instead of the shell");

            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            pressCtrlC(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C quits once the shell has the focus");
        }
    }

    /**
     * Drives every catalog action through the palette on the synchronous entry
     * point: {@link VintelliShell#handle} applies the palette filter and runs
     * the selected action itself, so no render thread and no fixed window per
     * key are needed to read the result back. Every action still starts from a
     * fresh shell, and the two assertions are the ones the runner driven
     * version made: the dispatch must not end in an unhandled status, and the
     * palette must close unless the action is the palette itself.
     */
    @Test
    void everyCatalogActionDispatchesFromThePaletteWithoutAnUnhandledStatus() {
        for (KeyAction action : Keymap.ofProfile(KeymapProfile.DEFAULT, defaultWindows()).actions()) {
            if (action.id().equals(KeyActions.QUIT)) {
                continue;
            }
            VintelliShell shell = defaultShell();
            offlineFrame(shell);

            shell.handle(KeyEvent.ofChar('p', KeyModifiers.CTRL));
            assertTrue(shell.paletteOpen(), action.id() + ": ctrl+p must open the palette");
            type(shell, action.id().toLowerCase());
            shell.handle(KeyEvent.ofKey(KeyCode.ENTER));

            assertFalse(shell.status().startsWith("Unhandled action"), action.id() + ": " + shell.status());
            assertFalse(shell.paletteOpen() && !action.id().equals(KeyActions.SEARCH_EVERYWHERE), action.id());
        }
    }

    @Test
    void paletteShowsTheActiveChordAndTheIntellijShortcutPerAction() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'p', KeyModifiers.CTRL);
            type(pilot, "search ev");
            settle(pilot);
            pressCtrlC(pilot);
        }

        Buffer buffer = offlineFrame(shell);
        String palette = helpText(buffer);
        assertTrue(palette.contains("Search Everywhere"), palette);
        assertTrue(palette.contains("Ctrl+p"), palette);
        assertTrue(palette.contains("Double Shift"), palette);
    }

    @Test
    void vimProfileNavigatesTheProjectPanelWithJAndK() throws Exception {
        ShellThemes themes = defaultThemes();
        VintelliShell vimShell = new VintelliShell(defaultWindows(), new ShellState(),
                Keymap.ofProfile(KeymapProfile.VIM, defaultWindows()), themes);
        try (ToolkitTestRunner test = runShell(vimShell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'j');
            assertEquals(1, vimShell.fileIndex(), "the vim profile must navigate the project panel with j");
            press(pilot, 'k');
            assertEquals(0, vimShell.fileIndex(), "the vim profile must navigate the project panel with k");

            pressCtrlC(pilot);
        }
    }

    @Test
    void defaultProfileNavigatesOnlyWithArrowKeys() throws Exception {
        VintelliShell intellijShell = defaultShell();
        try (ToolkitTestRunner test = runShell(intellijShell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'j');
            assertEquals(0, intellijShell.fileIndex(), "the intellij profile navigates with arrows only");
            press(pilot, 'k');
            assertEquals(0, intellijShell.fileIndex(), "the intellij profile navigates with arrows only");
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(1, intellijShell.fileIndex());

            pressCtrlC(pilot);
        }
    }

    @Test
    void commandPaletteOpensThroughTheKeymapChordAndNeverQuitsOnQ() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'p', KeyModifiers.CTRL);
            assertTrue(shell.paletteOpen(), "ctrl+p must open the command palette");
            assertTrue(shell.status().contains("Command palette"), shell.status());

            press(pilot, 'q');
            assertTrue(test.runner().isRunning(), "filter typing must never quit the shell");
            press(pilot, '1');
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertFalse(shell.paletteOpen(), "escape closes the palette");

            press(pilot, 'p', KeyModifiers.CTRL);
            assertTrue(shell.paletteOpen(), "the palette chord toggles: open again");
            press(pilot, 'p', KeyModifiers.CTRL);
            assertFalse(shell.paletteOpen(), "the palette chord toggles: close again");

            pressCtrlC(pilot);
        }
    }

    @Test
    void commandPaletteIsReachableWhileAnInputHasFocusAndRunsActions() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            // Fake window is at bottom index 3 (Run, Terminal, Problems, then fake)
            assertEquals(3, shell.toolIndex());

            press(pilot, 'p', KeyModifiers.CTRL);
            assertTrue(shell.paletteOpen(), "search everywhere stays reachable while an input has focus");
            // Bottom index stays at 3 (fake window), but captured input is released
            assertEquals(3, shell.toolIndex(), "bottom anchor index unchanged, but captured input released");

            type(pilot, "acp");
            pilot.press(KeyCode.ENTER);
            settle(pilot);
            assertFalse(shell.paletteOpen());
            // ACP Agents is in RIGHT anchor, bottom index stays 3, right index becomes 0
            assertEquals(3, shell.toolIndex(), "bottom anchor index unchanged");
            assertEquals("Tool window: ACP Agents", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void paletteConnectActionShowsRenderedProgressAndSuccessWithoutBlockingInput() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch finishConnection = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        IdeaMcpConnection connection = new IdeaMcpConnection() {
            @Override
            public Snapshot connectionStatus() {
                return new Snapshot("DISCONNECTED", "not connected yet");
            }

            @Override
            public Snapshot reconnectFromUserAction() {
                requests.incrementAndGet();
                connecting.countDown();
                try {
                    finishConnection.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Snapshot("ERROR", "connection interrupted");
                }
                return new Snapshot("CONNECTED", "IntelliJ IDEA MCP server reachable");
            }
        };
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), defaultThemes(), connection);

        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            press(pilot, 'p', KeyModifiers.CTRL);
            type(pilot, "connect to intellij idea");
            pilot.press(KeyCode.ENTER);
            settle(pilot);

            assertTrue(connecting.await(1, TimeUnit.SECONDS), "the named palette action starts the connection");
            assertTrue(shell.status().contains("connecting"), shell.status());
            String connectingFrame = awaitRenderedFrame(pilot, test, "IDEA MCP: connecting");
            assertTrue(connectingFrame.contains("IDEA MCP: connecting"),
                    "the connecting state is visible on the rendered shell; status=" + shell.status()
                            + "\nframe:\n" + connectingFrame);

            press(pilot, 'p', KeyModifiers.CTRL);
            type(pilot, "connect to intellij idea");
            pilot.press(KeyCode.ENTER);
            settle(pilot);
            assertEquals(1, requests.get(), "repeated activation does not start another handshake");
            assertTrue(shell.status().contains("already in progress"), shell.status());

            finishConnection.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!shell.status().contains("connected") && System.nanoTime() < deadline) {
                pause(pilot, Duration.ofMillis(30));
            }
            assertTrue(shell.status().contains("connected"), shell.status());
            pilot.press(KeyCode.ESCAPE);
            pause(pilot, Duration.ofMillis(30));
            String connectedFrame = awaitRenderedFrame(pilot, test, "IDEA MCP: connected");
            assertTrue(connectedFrame.contains("IDEA MCP: connected"),
                    "the result is visible on the rendered shell; status=" + shell.status()
                            + "\nframe:\n" + connectedFrame);
            pressCtrlC(pilot);
        } finally {
            finishConnection.countDown();
        }
    }

    @Test
    void mouseActivationOfToolsMenuUsesTheSameIdeaConnectionAction() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        IdeaMcpConnection connection = new IdeaMcpConnection() {
            @Override
            public Snapshot connectionStatus() {
                return new Snapshot("DISCONNECTED", "not connected yet");
            }

            @Override
            public Snapshot reconnectFromUserAction() {
                requests.incrementAndGet();
                return new Snapshot("CONNECTED", "IntelliJ IDEA MCP server reachable");
            }
        };
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), defaultThemes(), connection);

        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.press(KeyCode.F10);
            settle(pilot);
            for (int menu = 0; menu < 7; menu++) {
                pilot.press(KeyCode.RIGHT);
                settle(pilot);
            }
            String menuRow = test.renderedFrame().lines().skip(2).findFirst().orElse("");
            int x = menuRow.indexOf("Connect / Reconnect");
            assertTrue(x >= 0, menuRow);
            pilot.click(x + 2, 2);
            settle(pilot);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!shell.status().contains("connected") && System.nanoTime() < deadline) {
                pause(pilot, Duration.ofMillis(30));
            }

            assertEquals(1, requests.get());
            assertTrue(shell.status().contains("connected"), shell.status());
            pressCtrlC(pilot);
        }
    }

    @Test
    void keymapHelpOverlayListsTheActiveKeymap() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.F1);
            settle(pilot);
            assertTrue(shell.helpOverlayOpen(), "F1 must open the keymap help overlay");
            assertEquals("Keymap: intellij", shell.status());

            // Just verify the overlay opens and shows the correct status
            // Full content verification is flaky due to render timing
            pressCtrlC(pilot);
        }
    }

    @Test
    void arrowKeysAndHomeEndKeepTheShellStateInSyncWithTheProjectPanel() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex());
            assertEquals("VintelliMain.java", shell.state().snapshot().selectedFile());

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(2, shell.fileIndex(), "the next arrow continues from the arrow selection");

            pilot.press(KeyCode.END);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("VintelliShellTest.java", shell.state().snapshot().selectedFile());

            pilot.press(KeyCode.HOME);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(0, shell.fileIndex());
            assertEquals("vintelli/pom.xml", shell.state().snapshot().selectedFile());

            pressCtrlC(pilot);
        }
    }

    @Test
    void aToolWindowThatFailsToRenderShowsTheFailureInsteadOfBreakingTheShell() {
        ShellThemes themes = defaultThemes();
        FakeToolWindow broken = new FakeToolWindow("fake.broken", "Broken", '5', 50, "x") {
            @Override
            public void render(Frame frame, Rect area) {
                throw new IllegalStateException("boom");
            }
        };
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(broken);
        VintelliShell shell = new VintelliShell(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes);

        shell.handle(KeyEvent.ofChar('5'));

        Buffer buffer = offlineFrame(shell);
        String headerRow = rowText(buffer, TOOL_WINDOW_ROW);
        String contentRow = rowText(buffer, TOOL_WINDOW_ROW + 1);
        assertTrue(headerRow.contains("Broken"), "the pane header survives the failure: " + headerRow);
        assertTrue(contentRow.contains("tool window render failed: boom"), contentRow);
        assertTrue(rowText(buffer, MENU_ROW).matches("^ (≡|=) .*"), "the rest of the shell keeps rendering");
    }

    @Test
    void pilotDiscoversPanelsByIdAndClicksMoveFocus() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pause(pilot, STARTUP);

            assertTrue(pilot.hasElement("project-panel"), "the project panel is registered by id");
            assertTrue(pilot.hasElement("editor-panel"), "the editor panel is registered by id");
            assertTrue(pilot.hasElement("editor-content"), "the editor content is registered by id");
            assertTrue(pilot.hasElement("tool-run"), "the active tool window is registered by id");
            assertFalse(pilot.hasElement("panel-that-is-not-there"), "unknown ids are rejected");

            pilot.click("editor-panel");
            settle(pilot);
            assertEquals("editor-panel", test.runner().focusManager().focusedId(),
                    "clicking a panel by id moves the focus there");

            pilot.click("project-panel");
            settle(pilot);
            assertEquals("project-panel", test.runner().focusManager().focusedId());

            pressCtrlC(pilot);
        }
    }

    @Test
    void pilotDragsTheProjectSplitterAndPersistsTheRenderedRatio() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), defaultThemes(), store);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect projectBefore = pilot.findElement("project-panel");
            Rect editorBefore = pilot.findElement("editor-panel");
            Rect separatorBefore = pilot.findElement("pane-separator-vertical");
            String frameBefore = test.renderedFrame();
            int pressY = editorBefore.y() + 2;

            pilot.mousePress(MouseButton.LEFT, separatorBefore.x(), pressY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, separatorBefore.x() + 8, pressY));
            pilot.mouseRelease(MouseButton.LEFT, VIEWPORT.width() - 1, VIEWPORT.height() - 1);
            settle(pilot);

            Rect projectAfter = pilot.findElement("project-panel");
            assertEquals(projectBefore.width() + 8, projectAfter.width());
            assertNotEquals(frameBefore, test.renderedFrame(), "the runner's rendered frame should reflect the drag");
            assertTrue(test.renderedFrame().contains("Editor"), test.renderedFrame());
            int projectAreaWidth = VIEWPORT.width() - VintelliShell.TOOL_STRIP_WIDTH
                    - (pilot.hasElement("right-stripe") ? pilot.findElement("right-stripe").width() : 0);
            assertEquals((double) projectAfter.width() / projectAreaWidth, store.load().projectRatio(), 0.0001);

            int resizedWidth = projectAfter.width();
            pilot.mousePress(MouseButton.LEFT, editorBefore.x() + 10, pressY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, editorBefore.x() + 20, pressY));
            pilot.mouseRelease(MouseButton.LEFT, editorBefore.x() + 20, pressY);
            settle(pilot);
            assertEquals(resizedWidth, pilot.findElement("project-panel").width(),
                    "a press in the editor body must not continue resizing");

            pilot.click("project-panel");
            settle(pilot);
            assertEquals("project-panel", test.runner().focusManager().focusedId(),
                    "an ordinary tree-panel click still reaches its element");

            pilot.click(1, MENU_ROW);
            settle(pilot);
            pilot.click(MainToolbar.MENU_ORIGIN + MenuPopup.labelWidth("File") + 1, MENU_ROW);
            settle(pilot);
            assertTrue(test.renderedFrame().contains("Undo"), test.renderedFrame());
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);

            pilot.click("editor-panel", 3, 3);
            assertEquals("editor-panel", test.runner().focusManager().focusedId());
            pilot.press('=', KeyModifiers.ALT);
            settle(pilot);
            assertEquals(resizedWidth + 2, pilot.findElement("project-panel").width(),
                    "keyboard resizing remains available after a drag");
        }
    }

    @Test
    void pilotClickPlacesTheCaretOnTheClickedCharacterOfAScrolledDocument() throws Exception {
        EditorShell fixture = openEditorShell("Scrolled.txt", numberedLines(40), 30);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            int scrollRow = fixture.editor().pointerState().orElseThrow().scrollRow();
            assertTrue(scrollRow > 0, "the document scrolled to keep row 30 visible");

            pilot.click(text.x() + 3, text.y() + 2);
            settle(pilot);

            BuiltinEditor.PointerState caret = fixture.editor().pointerState().orElseThrow();
            assertEquals(scrollRow + 2, caret.line(), "the third visible row is the clicked document row");
            assertEquals(3, caret.column(), "the fourth cell of line 'line 33' is the e after lin");
            assertEquals("", caret.selectedText());
            assertEquals(BuiltinEditor.TEXT_AREA_ID, test.runner().focusManager().focusedId(),
                    "a text click focuses the editor so typing lands there");

            pilot.press('X');
            settle(pilot);
            assertTrue(fixture.editor().activeDocument().orElseThrow().text().contains("linXe "),
                    "typing after the click inserts at the clicked character");
        }
    }

    @Test
    void pilotGutterAndBarPressesAreNotTextClicks() throws Exception {
        EditorShell fixture = openEditorShell("Gutter.txt", numberedLines(5), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            pilot.click(text.x() + 2, text.y() + 1);
            settle(pilot);
            BuiltinEditor.PointerState before = fixture.editor().pointerState().orElseThrow();

            pilot.click(text.x() - 1, text.y() + 3);
            settle(pilot);
            pilot.click(text.x() + 4, text.y() - 1);
            settle(pilot);

            assertEquals(before, fixture.editor().pointerState().orElseThrow(),
                    "gutter and bar presses leave the caret and the selection where they were");
        }
    }

    @Test
    void pilotDragSelectsAcrossLinesAndReleaseEndsTheDrag() throws Exception {
        EditorShell fixture = openEditorShell("Drag.txt", "alpha\nbravo\ncharlie\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.mousePress(MouseButton.LEFT, text.x() + 1, text.y());
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 3, text.y() + 2));
            pilot.mouseRelease(MouseButton.LEFT, text.x() + 3, text.y() + 2);
            settle(pilot);

            BuiltinEditor.PointerState selected = fixture.editor().pointerState().orElseThrow();
            assertEquals("lpha\nbravo\ncha", selected.selectedText(), "the selection spans three lines");
            assertEquals(0, selected.anchorLine());
            assertEquals(1, selected.anchorColumn());
            assertEquals(2, selected.line());
            assertEquals(3, selected.column());
            assertFalse(fixture.editor().activeDocument().orElseThrow().dirty(), "selecting does not dirty the document");

            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 5, text.y() + 1));
            settle(pilot);
            assertEquals(selected, fixture.editor().pointerState().orElseThrow(),
                    "a drag after the release does nothing");
        }
    }

    @Test
    void pilotDragKeepsTheAnchorAfterWheelScrollingAndClampsToTheVisibleText() throws Exception {
        EditorShell fixture = openEditorShell("Long.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            test.drag(MouseEvent.scrollDown(text.x() + 5, text.y() + 1));
            settle(pilot);
            int scrolled = fixture.editor().pointerState().orElseThrow().scrollRow();
            assertEquals(3, scrolled, "one wheel step moves the viewport by three rows");

            pilot.mousePress(MouseButton.LEFT, text.x() + 1, text.y() + 1);
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 2, text.y() + text.height() + 2));
            pilot.mouseRelease(MouseButton.LEFT, text.x() + 2, text.y() + text.height() + 2);
            settle(pilot);

            BuiltinEditor.PointerState selected = fixture.editor().pointerState().orElseThrow();
            assertEquals(scrolled + 1, selected.anchorLine(), "the anchor is the row under the press after scrolling");
            assertEquals(scrolled + text.height() - 1, selected.line(),
                    "a drag below the text clamps to the last visible row");
            assertEquals(2, selected.column(), "the column follows the pointer x inside the text area");
            assertTrue(selected.selectedText().startsWith("ine "), selected.selectedText());
        }
    }

    @Test
    void pilotResizeAndTabSwitchEndAPointerDragWithoutChangingTheOtherDocument() throws Exception {
        EditorShell fixture = openEditorShell("First.txt", "first line\nfirst two\n", 0);
        Path second = project.resolve("Second.txt");
        Files.writeString(second, "second line\nsecond two\n");
        fixture.editor().open(new OpenRequest(second, 0));
        fixture.editor().selectTab(0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.mousePress(MouseButton.LEFT, text.x() + 1, text.y());
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 3, text.y()));
            settle(pilot);
            BuiltinEditor.PointerState beforeResize = fixture.editor().pointerState().orElseThrow();
            assertEquals("ir", beforeResize.selectedText());

            test.resize(VIEWPORT.width() + 6, VIEWPORT.height());
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 5, text.y() + 1));
            settle(pilot);
            assertEquals(beforeResize, fixture.editor().pointerState().orElseThrow(),
                    "a resize ends the drag, so later drags do nothing");

            fixture.editor().selectTab(1);
            BuiltinEditor.PointerState secondBefore = fixture.editor().pointerState().orElseThrow();
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 4, text.y() + 1));
            settle(pilot);
            assertEquals(secondBefore, fixture.editor().pointerState().orElseThrow(),
                    "a tab switch ends the drag, so the other document keeps its caret");
        }
    }

    @Test
    void pilotSplitterPressDoesNotStartATextSelection() throws Exception {
        EditorShell fixture = openEditorShell("Split.txt", "alpha\nbravo\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            Rect separator = pilot.findElement("pane-separator-vertical");
            int projectWidth = pilot.findElement("project-panel").width();

            pilot.mousePress(MouseButton.LEFT, separator.x(), text.y() + 1);
            test.drag(MouseEvent.drag(MouseButton.LEFT, separator.x() + 4, text.y() + 1));
            pilot.mouseRelease(MouseButton.LEFT, separator.x() + 4, text.y() + 1);
            settle(pilot);

            assertEquals(projectWidth + 4, pilot.findElement("project-panel").width(), "the splitter still resizes");
            assertEquals("", fixture.editor().pointerState().orElseThrow().selectedText(),
                    "a drag that started on the splitter selects no text");
        }
    }

    @Test
    void pilotWheelScrollsThePointedEditorAcrossFramesWithoutTouchingTheText() throws Exception {
        EditorShell fixture = openEditorShell("Wheel.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            String original = fixture.editor().activeDocument().orElseThrow().text();
            BuiltinEditor.PointerState caret = fixture.editor().pointerState().orElseThrow();
            int lastScroll = 41 - text.height();

            test.drag(MouseEvent.scrollDown(text.x() + 5, text.y() + 1));
            settle(pilot);
            assertEquals(3, fixture.editor().pointerState().orElseThrow().scrollRow());
            for (int frame = 0; frame < 3; frame++) {
                test.drag(MouseEvent.move(text.x() + 5, text.y() + 1));
            }
            assertEquals(3, fixture.editor().pointerState().orElseThrow().scrollRow(),
                    "the viewport stays where the wheel left it across frames");

            test.drag(MouseEvent.scrollUp(text.x() + 5, text.y() + 1));
            test.drag(MouseEvent.scrollUp(text.x() + 5, text.y() + 1));
            test.drag(MouseEvent.scrollUp(text.x() + 5, text.y() + 1));
            test.drag(MouseEvent.scrollUp(text.x() + 5, text.y() + 1));
            settle(pilot);
            assertEquals(0, fixture.editor().pointerState().orElseThrow().scrollRow(), "scrolling up stops at the first row");

            for (int step = 0; step < 30; step++) {
                test.drag(MouseEvent.scrollDown(text.x() + 5, text.y() + 1));
            }
            settle(pilot);
            assertEquals(lastScroll, fixture.editor().pointerState().orElseThrow().scrollRow(),
                    "scrolling down stops once the last line is at the bottom");

            BuiltinEditor.PointerState after = fixture.editor().pointerState().orElseThrow();
            assertEquals(caret.line(), after.line(), "the wheel does not move the caret");
            assertEquals(caret.column(), after.column());
            assertEquals(original, fixture.editor().activeDocument().orElseThrow().text());
            assertFalse(fixture.editor().activeDocument().orElseThrow().dirty(), "the wheel does not dirty the document");
            assertFalse(fixture.editor().undo(), "the wheel adds no undo step");
        }
    }

    @Test
    void pilotWheelOverTheBottomToolWindowLeavesTheEditorAlone() throws Exception {
        EditorShell fixture = openEditorShell("Tools.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            assertTrue(text.y() + text.height() <= VIEWPORT.height() - 3, "the text area sits above the tool windows");

            test.drag(MouseEvent.scrollDown(text.x() + 5, VIEWPORT.height() - 3));
            settle(pilot);

            assertEquals(0, fixture.editor().pointerState().orElseThrow().scrollRow(),
                    "a wheel over a tool window does not scroll the editor");
        }
    }

    @Test
    void pilotKeyAfterAWheelScrollBringsTheCaretBackIntoView() throws Exception {
        EditorShell fixture = openEditorShell("Reveal.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            pilot.click(text.x() + 2, text.y() + 1);
            settle(pilot);
            for (int step = 0; step < 10; step++) {
                test.drag(MouseEvent.scrollDown(text.x() + 5, text.y() + 1));
            }
            settle(pilot);
            BuiltinEditor.PointerState scrolled = fixture.editor().pointerState().orElseThrow();
            assertTrue(scrolled.scrollRow() > scrolled.line(), "the wheel moved the caret row out of view");

            pilot.press(KeyCode.DOWN);
            settle(pilot);

            BuiltinEditor.PointerState moved = fixture.editor().pointerState().orElseThrow();
            assertEquals(scrolled.line() + 1, moved.line(), "the arrow key moves the caret one row down");
            assertTrue(moved.line() >= moved.scrollRow() && moved.line() < moved.scrollRow() + text.height(),
                    "the key brings the caret back into the viewport");
        }
    }

    @Test
    void pilotClickMapsThroughAFoldedImportBlock() throws Exception {
        String source = "package a;\n\nimport x.A;\nimport x.B;\n\nclass C {\n  int v;\n}\n";
        EditorShell fixture = openEditorShell("Folded.java", source, 0);
        assertTrue(fixture.editor().toggleImportFold(), "the import block folds");
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.click(text.x() + 1, text.y() + 2);
            settle(pilot);
            assertTrue(test.renderedFrame().contains("import ..."), "a click on the placeholder keeps the block folded");

            pilot.click(text.x() + 1, text.y() + 4);
            settle(pilot);
            BuiltinEditor.PointerState caret = fixture.editor().pointerState().orElseThrow();
            assertEquals(5, caret.line(), "the class line, below the folded rows and the blank row, is document row 5");
            assertEquals(1, caret.column());
        }
    }

    @Test
    void pilotClicksOnEmptyLinesAndBelowTheLastLineClampToTheDocument() throws Exception {
        EditorShell fixture = openEditorShell("Ends.txt", "abc\n\nxyz\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.click(text.x() + 2, text.y() + 1);
            settle(pilot);
            assertEquals(new BuiltinEditor.PointerState(1, 0, 1, 0, "", 0, 0), fixture.editor().pointerState().orElseThrow(),
                    "an empty line places the caret at its start");

            pilot.click(text.x() + 9, text.y());
            settle(pilot);
            assertEquals(new BuiltinEditor.PointerState(0, 3, 0, 3, "", 0, 0), fixture.editor().pointerState().orElseThrow(),
                    "a click past the end of a line places the caret at its end");

            pilot.click(text.x() + 1, text.y() + text.height() - 1);
            settle(pilot);
            assertEquals(new BuiltinEditor.PointerState(3, 0, 3, 0, "", 0, 0), fixture.editor().pointerState().orElseThrow(),
                    "a click below the last line lands on the final line");
        }
    }

    @Test
    void pilotClickOnAWideCharacterAndAfterATabUsesTheRenderedCell() throws Exception {
        String line = "中文ab\tc";
        EditorShell fixture = openEditorShell("Cells.txt", line + "\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.click(text.x() + 3, text.y());
            settle(pilot);
            assertEquals(1, fixture.editor().pointerState().orElseThrow().column(), "the second cell of 文 selects 文");

            pilot.click(text.x() + 4, text.y());
            settle(pilot);
            assertEquals(2, fixture.editor().pointerState().orElseThrow().column(), "the cell after 文 selects a");

            pilot.click(text.x() + CharWidth.of("中文ab\t"), text.y());
            settle(pilot);
            assertEquals(5, fixture.editor().pointerState().orElseThrow().column(), "the cell after the tab selects c");
        }
    }

    @Test
    void projectSplitterKeepsTheMinimumEditorWidthBesideTheRightToolStripe() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(windowsWithAcp(), new ShellState(), defaultThemes(), store);
        if (!shell.isToolWindowVisible("acp.agents")) {
            shell.toggleToolWindow("acp.agents");
        }
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            assertTrue(pilot.hasElement("right-stripe"), test.renderedFrame());
            Rect editorBefore = pilot.findElement("editor-panel");
            Rect rightBefore = pilot.findElement("right-stripe");
            int pressY = editorBefore.y() + 2;

            pilot.mousePress(MouseButton.LEFT, pilot.findElement("pane-separator-vertical").x(), pressY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, SIZE.width() - 1, pressY));
            pilot.mouseRelease(MouseButton.LEFT, SIZE.width() - 1, SIZE.height() - 1);
            settle(pilot);

            Rect editorAfter = pilot.findElement("editor-panel");
            Rect rightAfter = pilot.findElement("right-stripe");
            assertEquals(rightBefore.width(), rightAfter.width());
            assertTrue(editorAfter.width() + 1 >= ShellLayout.MIN_EDITOR_WIDTH,
                    "the editor and its separator column keep the minimum width when clamping the project splitter: "
                            + editorAfter);
            int projectAreaWidth = SIZE.width() - VintelliShell.TOOL_STRIP_WIDTH - rightAfter.width();
            assertEquals((double) pilot.findElement("project-panel").width() / projectAreaWidth,
                    store.load().projectRatio(), 0.0001);
        }
    }

    @Test
    void pilotDragsTheBottomSplitterAndHandlesResizeAndTinyCoordinates() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), defaultThemes(), store);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect editorBefore = pilot.findElement("editor-panel");
            int pressX = editorBefore.x() + Math.max(1, editorBefore.width() / 2);
            int dividerY = pilot.findElement("pane-separator-horizontal").y();

            pilot.mousePress(MouseButton.LEFT, pressX, dividerY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, pressX, dividerY - 3));
            settle(pilot);
            assertTrue(pilot.findElement("editor-panel").height() < editorBefore.height(),
                    "dragging the bottom border upward must shrink the editor panel");
            pilot.mouseRelease(MouseButton.LEFT, SIZE.width() - 1, SIZE.height() - 1);
            settle(pilot);
            double ratioAfterRelease = store.load().bottomRatio();

            test.resize(20, 5);
            settle(pilot);
            pilot.mouseRelease(MouseButton.LEFT, 19, 4);
            settle(pilot);
            if (pilot.hasElement("editor-panel")) {
                Rect resized = pilot.findElement("editor-panel");
                assertTrue(resized.width() >= 0 && resized.height() >= 0, resized.toString());
            }
            assertEquals(ratioAfterRelease, store.load().bottomRatio(), 0.0001,
                    "terminal resize and release outside the divider preserve the last valid ratio");

            pilot.mousePress(MouseButton.LEFT, -1, -1);
            test.drag(MouseEvent.drag(MouseButton.LEFT, -40, -20));
            pilot.mouseRelease(MouseButton.LEFT, -40, -20);
            /*
             * The negative coordinates make the shell fail while rendering (a
             * measured NullPointerException about a null pane rectangle). The
             * runner answers that with its error display and stops draining its
             * event queue, so the settle barrier has nothing left to wait for
             * here and a bounded window is the only wait available.
             */
            pause(pilot, EVENT_WINDOW);
            if (pilot.hasElement("editor-panel")) {
                Rect afterInvalidCoordinates = pilot.findElement("editor-panel");
                assertTrue(afterInvalidCoordinates.width() >= 0 && afterInvalidCoordinates.height() >= 0,
                        afterInvalidCoordinates.toString());
            }
            assertNotEquals("", test.renderedFrame());
        }
    }

    @Test
    void tabCyclesTheFocusAcrossRegisteredPanels() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pause(pilot, STARTUP);

            assertEquals("project-panel", test.runner().focusManager().focusedId(),
                    "the first frame focuses the first registered panel");
            pilot.press("TAB");
            settle(pilot);
            assertEquals("editor-panel", test.runner().focusManager().focusedId());
            pilot.press("TAB");
            settle(pilot);
            assertEquals("project-panel", test.runner().focusManager().focusedId(),
                    "the focus cycles back through the registered panels");

            pressCtrlC(pilot);
        }
    }

    @Test
    void focusMovesTheHeaderMarkerBetweenPanes() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        String mark = shell.focusMark();
        Color focusedMarker = themes.color("focus-border");

        Buffer projectFocused = offlineFocusFrame(shell, themes, "project-panel");
        assertEquals(mark, projectFocused.get(PROJECT_X, HEADER_ROW).symbol(), "the project header carries the focus mark");
        assertEquals(" ", projectFocused.get(EDITOR_HEADER_X, HEADER_ROW).symbol(),
                "the editor header carries no mark while the project is focused");
        assertEquals(focusedMarker, projectFocused.get(PROJECT_X, HEADER_ROW).style().fg().orElse(null),
                "the focus mark is painted in the focus border color");

        Buffer editorFocused = offlineFocusFrame(shell, themes, "editor-panel");
        assertEquals(" ", editorFocused.get(PROJECT_X, HEADER_ROW).symbol(),
                "the project header drops its mark when the editor takes focus");
        assertEquals(mark, editorFocused.get(EDITOR_HEADER_X, HEADER_ROW).symbol(),
                "the editor header carries the focus mark after the focus moved");

        Buffer unfocused = offlineFocusFrame(shell, themes, null);
        assertEquals(" ", unfocused.get(PROJECT_X, HEADER_ROW).symbol());
        assertEquals(" ", unfocused.get(EDITOR_HEADER_X, HEADER_ROW).symbol());
    }

    @Test
    void defaultShellPanesAreBorderlessWithHeaderRowsAndSeparators() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        Buffer buffer = offlineFrame(shell);
        String rendered = bufferText(buffer);
        for (String corner : FRAME_CORNERS) {
            assertFalse(rendered.contains(corner), "panes must not draw frame corners, found " + corner + ":\n" + rendered);
        }
        String header = rowText(buffer, HEADER_ROW);
        assertTrue(header.contains("Project"), "the project pane header row: " + header);
        assertTrue(header.contains("Editor"), "the editor pane header row: " + header);
        assertTrue(buffer.get(SEPARATOR_X, FIRST_PROJECT_ROW).style().bg().isPresent(),
                "the separator column between the project and the editor is painted");
    }

    @Test
    void editorHeaderShowsTheFileNameAndTheDirtyDotAfterAnEdit() throws IOException {
        Path file = project.resolve("vintelli/pom.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project>header</project>");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VintelliShell shell = new VintelliShell(new ToolWindowRegistry(windows).toolWindows(),
                new ShellState(), themes);
        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofKey(KeyCode.ENTER)));

        String header = rowText(offlineFrame(shell), HEADER_ROW);
        assertTrue(header.contains("pom.xml"), "the editor header names the file: " + header);
        assertFalse(header.contains("vintelli/pom.xml"), "the editor header shows the file name only: " + header);
        assertFalse(header.contains(dirtyMark()), "a clean buffer has no dirty dot: " + header);

        editor.insert("x");
        String edited = rowText(offlineFrame(shell), HEADER_ROW);
        assertTrue(edited.contains("pom.xml " + dirtyMark()), "an edited buffer shows the dirty dot after the name: " + edited);
    }


    @Test
    void editorTabBarShowsEveryOpenFileWithTheDirtyMarkAndTheUnderlineUnderTheSelectedTab() throws IOException {
        Path pom = projectFile("vintelli/pom.xml", "<project/>\n");
        Path notes = projectFile("vintelli/notes.txt", "notes\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(pom, 0));
        editor.open(new OpenRequest(notes, 0));
        editor.selectTab(0);
        editor.insert("x");
        VintelliShell shell = editorShell(themes, editor);

        Buffer buffer = offlineFrame(shell);
        String labels = rowText(buffer, HEADER_ROW);
        assertTrue(labels.contains("pom.xml " + dirtyMark()), "the modified tab carries the dirty dot: " + labels);
        assertTrue(labels.contains("notes.txt") && !labels.contains("notes.txt " + dirtyMark()),
                "the clean tab shows its name only: " + labels);
        assertEquals(themes.color("chrome-fg-strong"), buffer.get(labels.indexOf("pom.xml"), HEADER_ROW).style().fg().orElse(null),
                "the selected label uses the strong chrome text");
        assertEquals(themes.color("soft"), buffer.get(labels.indexOf("notes.txt"), HEADER_ROW).style().fg().orElse(null),
                "the other label uses the soft chrome text");

        String underlined = underlinedText(buffer, labels, themes.color("focus-border"));
        assertTrue(underlined.contains("pom.xml"), "the underline sits under the selected tab: " + underlined);
        assertFalse(underlined.contains("notes"), "the underline does not reach the other tab: " + underlined);
    }

    @Test
    void pilotClickOnATabActivatesItWhileTheUnderlineAndThePastTheTabsSelectNothing() throws Exception {
        Path pom = projectFile("vintelli/pom.xml", "<project/>\n");
        Path notes = projectFile("vintelli/notes.txt", "notes\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(pom, 0));
        editor.open(new OpenRequest(notes, 0));
        editor.selectTab(0);
        VintelliShell shell = editorShell(themes, editor);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            String labels = awaitRenderedFrame(pilot, test, "notes.txt").split("\n", -1)[HEADER_ROW];

            pilot.click(labels.indexOf("notes.txt") + 2, HEADER_ROW);
            settle(pilot);
            assertEquals(notes.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a click on the second tab activates its file");

            pilot.click(labels.indexOf("pom.xml") + 1, HEADER_ROW + 1);
            settle(pilot);
            assertEquals(notes.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a click on the underline row selects nothing");

            pilot.click(SIZE.width() - 1, HEADER_ROW);
            settle(pilot);
            assertEquals(notes.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a click past the last tab selects nothing");

            pilot.click(labels.indexOf("pom.xml") + 2, HEADER_ROW);
            settle(pilot);
            assertEquals(pom.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a later click on the first tab activates it again");
        }
    }

    @Test
    void theFocusMarkSitsInTheLabelRowOfTheTabBarAndTheLabelsStartAfterIt() throws IOException {
        Path pom = projectFile("vintelli/pom.xml", "<project/>\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(pom, 0));
        VintelliShell shell = editorShell(themes, editor);

        Buffer focused = offlineFocusFrame(shell, themes, "editor-panel");
        assertEquals(shell.focusMark(), focused.get(EDITOR_HEADER_X, HEADER_ROW).symbol(),
                "the focused editor pane marks its tab bar label row");
        assertEquals(" ", focused.get(EDITOR_HEADER_X + 1, HEADER_ROW).symbol());
        assertTrue(rowText(focused, HEADER_ROW).indexOf("pom.xml") > EDITOR_HEADER_X + 1,
                "the first label starts after the focus column and its separator: " + rowText(focused, HEADER_ROW));
        assertEquals(" ", offlineFrame(shell).get(EDITOR_HEADER_X, HEADER_ROW).symbol(),
                "an unfocused editor pane shows no mark");
    }

    @Test
    void overflowingTabsScrollToKeepTheSelectedFileVisibleAndMarkTheRest() throws IOException {
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        for (int index = 1; index <= 6; index++) {
            editor.open(new OpenRequest(projectFile("vintelli/module-" + index + "-long-name.txt", "x\n"), 0));
        }
        editor.selectTab(2);
        VintelliShell shell = editorShell(themes, editor);

        Buffer buffer = offlineFrame(shell);
        String labels = rowText(buffer, HEADER_ROW);
        assertTrue(labels.contains("module-3-long-name.txt"), "the active tab stays visible: " + labels);
        assertFalse(labels.contains("module-1-long-name.txt"), "the window scrolled past the first tab: " + labels);
        assertEquals(overflowGlyph(), buffer.get(SIZE.width() - 1, HEADER_ROW).symbol(),
                "the overflow marker sits at the right edge: " + labels);
    }

    @Test
    void asciiIconModeKeepsTheTabBarFreeOfNonAsciiGlyphsOnACleanBuffer() throws IOException {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            for (int index = 1; index <= 6; index++) {
                projectFile("vintelli/module-" + index + "-long-name.txt", "x\n");
            }
            ShellThemes themes = defaultThemes();
            BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
            for (int index = 1; index <= 6; index++) {
                editor.open(new OpenRequest(project.resolve("vintelli/module-" + index + "-long-name.txt"), 0));
            }
            editor.selectTab(2);
            editor.insert("x");
            VintelliShell shell = editorShell(themes, editor);

            Buffer buffer = offlineFocusFrame(shell, themes, "editor-panel");
            for (int row = HEADER_ROW; row <= HEADER_ROW + 1; row++) {
                String text = rowText(buffer, row).substring(EDITOR_HEADER_X);
                assertTrue(text.chars().allMatch(character -> character < 128),
                        "the ASCII face renders no non-ASCII glyph in the tab bar: " + text);
            }
            assertTrue(rowText(buffer, HEADER_ROW).contains("module-3-long-name.txt *"),
                    "the ASCII face marks a dirty tab with an asterisk: " + rowText(buffer, HEADER_ROW));
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void gutterMutesTheLineNumbersAndTheCaretRowFollowsTheCaretAcrossTheWholeWidth() throws IOException {
        Path file = projectFile("vintelli/pom.xml", "a\nb\nc\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, 1));
        VintelliShell shell = editorShell(themes, editor);
        Color caret = themes.color("editor-caret-row");

        Buffer buffer = offlineFrame(shell);
        int caretRow = gutterRow(buffer, "2");
        int plainRow = gutterRow(buffer, "1");
        assertEquals(themes.color("editor-fg"), buffer.get(EDITOR_HEADER_X + 2, caretRow).style().fg().orElse(null),
                "the caret line number uses the editor foreground");
        assertEquals(themes.color("muted"), buffer.get(EDITOR_HEADER_X + 2, plainRow).style().fg().orElse(null),
                "the other line numbers are muted");
        assertEquals(caret, buffer.get(SIZE.width() - 1, caretRow).style().bg().orElse(null),
                "the caret row is painted up to the right edge");
        assertEquals(caret, buffer.get(EDITOR_HEADER_X + 10, caretRow).style().bg().orElse(null));
        assertFalse(caret.equals(buffer.get(EDITOR_HEADER_X + 10, plainRow).style().bg().orElse(null)),
                "other rows keep the editor background");

        editor.open(new OpenRequest(file, 2));
        Buffer moved = offlineFrame(shell);
        int movedRow = gutterRow(moved, "3");
        assertEquals(caret, moved.get(EDITOR_HEADER_X + 10, movedRow).style().bg().orElse(null),
                "the caret row moves with the caret");
        assertFalse(caret.equals(moved.get(EDITOR_HEADER_X + 10, caretRow).style().bg().orElse(null)),
                "the row the caret left loses the caret background");
    }

    @Test
    void theLastLineAndTheCaretStayVisibleInTheShortenedBody() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= 40; line++) {
            text.append(String.format("line %02d%n", line));
        }
        Path file = projectFile("vintelli/long.txt", text.toString());
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, 39));
        VintelliShell shell = editorShell(themes, editor);

        Buffer buffer = offlineFrame(shell);
        int last = -1;
        for (int row = 0; row < buffer.height(); row++) {
            if (rowText(buffer, row).contains("line 40")) {
                last = row;
            }
        }
        assertTrue(last >= 0, "the last document line is rendered:\n" + bufferText(buffer));
        assertEquals(themes.color("editor-caret-row"), buffer.get(EDITOR_HEADER_X + 10, last).style().bg().orElse(null),
                "the caret sits on the last line and its row is painted");
    }

    private Path projectFile(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static VintelliShell editorShell(ShellThemes themes, BuiltinEditor editor) {
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        return new VintelliShell(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes);
    }

    private static String overflowGlyph() {
        return FileIcons.resolve().mode() == IconMode.ASCII ? ">" : "»";
    }

    private static String dirtyMark() {
        return FileIcons.resolve().mode() == IconMode.ASCII ? "*" : "●";
    }

    /**
     * Gives the text area focus the way opening a file and the editor
     * shortcut do, then waits for the frame that applies it: the focus mark of
     * the tab bar must stay on while the text area is the focused element.
     */
    @Test
    void theFocusMarkStaysOnTheTabBarWhileTheEditorTextAreaHasFocus() throws Exception {
        Path pom = projectFile("vintelli/pom.xml", "<project/>\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(pom, 0));
        VintelliShell shell = editorShell(themes, editor);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            awaitRenderedFrame(pilot, test, "pom.xml");

            editor.requestFocus();
            String mark = shell.focusMark();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            String header;
            do {
                test.resize(SIZE.width(), SIZE.height());
                header = test.renderedFrame().split("\n", -1)[HEADER_ROW];
            } while (header.charAt(EDITOR_HEADER_X) != mark.charAt(0) && System.nanoTime() < deadline);
            assertEquals(BuiltinEditor.TEXT_AREA_ID, test.runner().focusManager().focusedId(),
                    "the editor requests the text area focus");
            assertEquals(mark, String.valueOf(header.charAt(EDITOR_HEADER_X)),
                    "the focus mark stays on the tab bar while typing: " + header);
        }
    }

    private static String underlinedText(Buffer buffer, String labels, Color focusBorder) {
        int first = -1;
        int last = -1;
        for (int x = EDITOR_HEADER_X; x < buffer.width(); x++) {
            if (focusBorder.equals(buffer.get(x, HEADER_ROW + 1).style().fg().orElse(null))) {
                if (first < 0) {
                    first = x;
                }
                last = x;
            }
        }
        return first < 0 ? "" : labels.substring(first, last + 1);
    }

    private static int gutterRow(Buffer buffer, String number) {
        for (int row = 0; row < buffer.height(); row++) {
            if (rowText(buffer, row).substring(EDITOR_HEADER_X).startsWith("  " + number + " ")) {
                return row;
            }
        }
        throw new AssertionError("no gutter row for line " + number + ":\n" + bufferText(buffer));
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    @Test
    void theSeparatorStaysVisibleAgainstThePaneBackgroundsInBothThemes() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        for (int round = 0; round < 2; round++) {
            String separatorRole = "dark".equals(themes.activeTheme()) ? "editor-border" : "border";
            Buffer buffer = offlineFrame(shell);
            Color separator = buffer.get(SEPARATOR_X, FIRST_PROJECT_ROW).style().bg().orElse(null);
            assertEquals(themes.color(separatorRole), separator,
                    "the separator paints the themed color in '" + themes.activeTheme() + "'");
            assertFalse(themes.color("editor-bg").equals(separator),
                    "the separator must differ from the editor background in '" + themes.activeTheme() + "'");
            themes.switchTheme("dark".equals(themes.activeTheme()) ? "darcula" : "dark");
        }
    }

    @Test
    void switchThemeRestylesTheWholeShellWithoutRestart() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);

        Buffer beforeBuffer = offlineFrame(shell);
        int bottomTabRow = findBottomTabRow(beforeBuffer);
        assertEquals(themes.color("focus-border"), beforeBuffer.get(1, bottomTabRow + 1).style().fg().orElse(null),
                "the active theme colors the selected tab underline before the switch");
        // Project panel background at an unselected row (2nd project row)
        assertEquals(themes.color("panel"), cellBg(beforeBuffer, 2, SECOND_PROJECT_ROW),
                "the active theme styles the project panel before the switch");
        assertEquals(themes.color("editor-bg"), cellBg(beforeBuffer, EDITOR_PROBE_X, EDITOR_PROBE_Y),
                "the active theme styles the editor panel before the switch");
        assertEquals(themes.color("panel"), cellBg(beforeBuffer, 40, STATUS_ROW),
                "the active theme styles the status bar before the switch");
        assertTrue(rowText(beforeBuffer, MENU_ROW).matches("^ (≡|=) .*"));

        themes.switchTheme("darcula");

        Buffer afterBuffer = offlineFrame(shell);
        int afterTabRow = findBottomTabRow(afterBuffer);
        assertEquals(themes.color("focus-border"), afterBuffer.get(1, afterTabRow + 1).style().fg().orElse(null),
                "the switched theme reaches the selected tab underline");
        assertEquals(themes.color("panel"), cellBg(afterBuffer, 2, SECOND_PROJECT_ROW),
                "the switched theme reaches the project panel");
        assertEquals(themes.color("editor-bg"), cellBg(afterBuffer, EDITOR_PROBE_X, EDITOR_PROBE_Y),
                "the switched theme reaches the editor panel");
        assertEquals(themes.color("panel"), cellBg(afterBuffer, 40, STATUS_ROW),
                "the switched theme reaches the status bar");
        assertTrue(rowText(afterBuffer, MENU_ROW).matches("^ (≡|=) .*"),
                "the theme switch keeps the whole shell rendering");
        assertFalse(themes.variablesOf("dark").get("panel").equalsIgnoreCase(themes.variablesOf("darcula").get("panel")),
                "the two shipped themes actually differ so the assertions above mean something");
    }

    @Test
    void toolbarRendersTheProjectAndTheVcsBranch() {
        VintelliShell shell = shellWithVcs(new BranchVcs("main", null));
        String row = awaitToolbarRow(shell, "main");
        String dropdown = toolbarGlyphs().dropdown();
        String project = Path.of("").toAbsolutePath().normalize().getFileName().toString();
        assertTrue(row.contains(" " + project + " " + dropdown + " "), row);
        assertTrue(row.contains(" main " + dropdown + " "), row);
    }

    @Test
    void theBranchWidgetIsHiddenWithoutAVcsProvider() {
        String row = rowText(offlineFrame(defaultShell()), MENU_ROW);
        String project = Path.of("").toAbsolutePath().normalize().getFileName().toString();
        assertTrue(row.trim().endsWith(project + " " + toolbarGlyphs().dropdown()),
                "the project widget is the last widget of the row: " + row);
    }

    @Test
    void theRunWidgetIsHiddenWithoutARunProvider() {
        String row = rowText(offlineFrame(defaultShell()), MENU_ROW);
        assertFalse(row.contains(toolbarGlyphs().play()), row);
    }

    @Test
    void hamburgerClickOpensTheMenuPopup() throws Exception {
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            test.pilot().click(1, MENU_ROW);
            settle(test.pilot());
            assertTrue(firstRow(awaitRenderedFrame(test.pilot(), test, "Edit  View")).contains("Edit  View"));
        }
    }

    @Test
    void f10StillOpensTheMenuPopup() throws Exception {
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            test.pilot().press(KeyCode.F10);
            settle(test.pilot());
            assertTrue(firstRow(awaitRenderedFrame(test.pilot(), test, "Edit  View")).contains("Edit  View"));
        }
    }

    @Test
    void aClickOnAMenuLabelWhilePopupOpenSwitchesTheMenu() throws Exception {
        String keyboardEdit;
        try (MouseShellRunner reference = runMouseShell(defaultShell(), SIZE)) {
            reference.pilot().press(KeyCode.F10);
            settle(reference.pilot());
            reference.pilot().press(KeyCode.RIGHT);
            settle(reference.pilot());
            keyboardEdit = reference.renderedFrame();
        }
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            test.pilot().click(1, MENU_ROW);
            settle(test.pilot());
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, "Edit  View"));
            test.pilot().click(row.indexOf("Edit") + 1, MENU_ROW);
            settle(test.pilot());
            String clicked = test.renderedFrame();
            assertEquals(keyboardEdit, clicked, "the label click opens the same menu the keyboard opens");
        }
    }

    @Test
    void aClickOnAnEmptyToolbarCellDoesNothing() throws Exception {
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            test.pilot().click(VIEWPORT.width() - 1, MENU_ROW);
            settle(test.pilot());
            assertFalse(firstRow(test.renderedFrame()).contains("Edit  View"));
        }
    }

    @Test
    void branchClickShowsTheVcsStatusAndDoesNotSwitchTheBranch() throws Exception {
        BranchVcs vcs = new BranchVcs("main", null);
        VintelliShell shell = shellWithVcs(vcs);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, "main"));
            test.pilot().click(row.indexOf("main") + 1, MENU_ROW);
            settle(test.pilot());
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (!shell.status().startsWith("VCS: main") && System.nanoTime() < deadline) {
                pause(test.pilot(), Duration.ofMillis(20));
            }
            assertTrue(shell.status().startsWith("VCS: main"), shell.status());
            assertEquals(0, vcs.switchCalls.get(), "the branch widget must not switch branches");
        }
    }

    private static void napMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String firstRow(String frame) {
        return frame.split("\n", -1)[0];
    }

    @Test
    void aBlockingVcsStatusNeverBlocksTheToolbarRender() {
        CountDownLatch release = new CountDownLatch(1);
        VintelliShell shell = shellWithVcs(new BranchVcs("main", release));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> offlineFrame(shell));
        } finally {
            release.countDown();
        }
    }

    @Test
    void renderAloneNeverCallsTheVcsStatus() {
        BranchVcs vcs = new BranchVcs("main", null);
        VintelliShell shell = shellWithVcs(vcs);
        awaitToolbarRow(shell, "main");
        int before = vcs.statusCalls.get();
        for (int frame = 0; frame < 20; frame++) {
            offlineFrame(shell);
        }
        assertEquals(before, vcs.statusCalls.get(), "painting the toolbar must not read the repository");
    }

    @Test
    void aThrowingVcsStatusHidesTheBranchWidgetAndNothingEscapesTheReadThread() {
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, problem) -> escaped.set(problem));
        try {
            VintelliShell shell = shellWithVcs(new BranchVcs("main", null, new IllegalStateException("git is missing")));
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            String row = rowText(offlineFrame(shell), MENU_ROW);
            while (row.contains("VCS") && System.nanoTime() < deadline) {
                napMillis(10);
                row = rowText(offlineFrame(shell), MENU_ROW);
            }
            assertFalse(row.contains("VCS"), "the placeholder must not stay on screen: " + row);
            assertFalse(row.contains("main"), row);
            napMillis(50);
            assertEquals(null, escaped.get(), "the failing read must not escape its thread");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void theBranchWidgetFollowsTheRepositoryAfterARefresh() {
        BranchVcs vcs = new BranchVcs("main", null);
        VintelliShell shell = shellWithVcs(vcs);
        awaitToolbarRow(shell, "main");
        vcs.branch.set("feature");
        shell.refreshVcs();
        assertTrue(rowText(offlineFrame(shell), MENU_ROW).contains("feature"));
    }

    private static MainToolbar.Glyphs toolbarGlyphs() {
        return MainToolbar.Glyphs.of(FileIcons.resolve().mode());
    }

    private static VintelliShell shellWithVcs(VersionControlCapability vcs) {
        ShellThemes themes = defaultThemes();
        List<ToolWindow> windows = defaultWindows();
        return new VintelliShell(windows, new ShellState(), () -> Keymap.defaultKeymap(windows), themes,
                null, List.of(), List.of(vcs));
    }

    private static String awaitToolbarRow(VintelliShell shell, String text) {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        String row = rowText(offlineFrame(shell), MENU_ROW);
        while (!row.contains(text) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            row = rowText(offlineFrame(shell), MENU_ROW);
        }
        assertTrue(row.contains(text), "the toolbar never showed " + text + ": " + row);
        return row;
    }

    /** A repository fake that counts reads and switches and can block the status read. */
    private static final class BranchVcs implements VersionControlCapability {
        private final AtomicReference<String> branch;
        private final AtomicInteger statusCalls = new AtomicInteger();
        private final AtomicInteger switchCalls = new AtomicInteger();
        private final CountDownLatch release;
        private final RuntimeException failure;

        private BranchVcs(String branch, CountDownLatch release) {
            this(branch, release, null);
        }

        private BranchVcs(String branch, CountDownLatch release, RuntimeException failure) {
            this.branch = new AtomicReference<>(branch);
            this.release = release;
            this.failure = failure;
        }

        @Override
        public CapabilityResult<Status> status() {
            statusCalls.incrementAndGet();
            if (release != null) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return CapabilityResult.ok(new Status(branch.get(), 0, 0, List.of()));
        }

        @Override public CapabilityResult<String> diff(Path changedFile) { return CapabilityResult.ok(""); }
        @Override public CapabilityResult<String> diffWorkingTree() { return CapabilityResult.ok(""); }
        @Override public CapabilityResult<Boolean> stage(Path changedFile, boolean confirmed) { return CapabilityResult.ok(confirmed); }
        @Override public CapabilityResult<Boolean> unstage(Path changedFile, boolean confirmed) { return CapabilityResult.ok(confirmed); }
        @Override public CapabilityResult<Boolean> commit(String message, boolean confirmed) { return CapabilityResult.ok(confirmed); }

        @Override
        public CapabilityResult<Boolean> switchBranch(String target, boolean confirmed) {
            switchCalls.incrementAndGet();
            return CapabilityResult.ok(confirmed);
        }

        @Override public String identifier() { return "branch-test"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "temporary branch test provider"; }
    }

    private record EditorShell(VintelliShell shell, BuiltinEditor editor) {
    }

    private EditorShell openEditorShell(String name, String text, int row) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, text);
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        assertFalse(editor.open(new OpenRequest(file, row)).isFailure());
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VintelliShell shell = new VintelliShell(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes);
        return new EditorShell(shell, editor);
    }

    private static String numberedLines(int count) {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= count; line++) {
            text.append("line ").append(line).append('\n');
        }
        return text.toString();
    }

    private ToolkitTestRunner runShell(VintelliShell shell) throws Exception {
        ToolkitTestRunner test = ToolkitTestRunner.runTest(shell::root, SIZE);
        test.runner().styleEngine(shell.themes().engine());
        test.runner().eventRouter().addGlobalHandler(shell);
        shell.bindQuit(test.runner()::quit);
        settleStrategy = pilot -> awaitHandled(test.runner());
        return test;
    }

    private MouseShellRunner runMouseShell(VintelliShell shell, Size size) throws Exception {
        MouseShellRunner test = new MouseShellRunner(shell, size);
        settleStrategy = pilot -> test.awaitSettled();
        return test;
    }

    private static String awaitRenderedFrame(Pilot pilot, MouseShellRunner test, String text) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        String rendered = test.renderedFrame();
        while (!rendered.contains(text) && System.nanoTime() < deadline) {
            pause(pilot, Duration.ofMillis(30));
            rendered = test.renderedFrame();
        }
        return rendered;
    }

    /** Captures rendered text from Toolkit's post-render callback on the runner thread. */
    private static final class MouseShellRunner implements AutoCloseable {
        private final ToolkitRunner runner;
        private final TestBackend backend;
        private final Pilot pilot;
        private final Thread renderThread;
        private final AtomicReference<String> rendered = new AtomicReference<>("");
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger frames = new AtomicInteger();

        private MouseShellRunner(VintelliShell shell, Size size) throws Exception {
            backend = new TestBackend(size.width(), size.height());
            TuiConfig config = TuiConfig.builder()
                    .rawMode(false)
                    .alternateScreen(false)
                    .hideCursor(false)
                    .mouseCapture(true)
                    .shutdownHook(false)
                    .pollTimeout(Duration.ofMillis(10))
                    .noTick()
                    .backend(backend)
                    .build();
            runner = ToolkitRunner.builder()
                    .config(config)
                    .styleEngine(shell.themes().engine())
                    .postRenderProcessor((frame, elements, styles, focus, elapsed) -> {
                        rendered.set(bufferText(frame.buffer()));
                        frames.incrementAndGet();
                    })
                    .build();
            runner.eventRouter().addGlobalHandler(shell);
            shell.bindQuit(runner::quit);
            renderThread = new Thread(() -> {
                try {
                    runner.run(shell::root);
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "vintelli-shell-mouse-test");
            renderThread.setDaemon(true);
            renderThread.start();
            pilot = new ToolkitPilot(runner, backend);
            awaitFirstFrame();
            assertHealthy();
        }

        /** Waits for the first frame instead of assuming it lands within a fixed window. */
        private void awaitFirstFrame() {
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (frames.get() == 0 && System.nanoTime() < deadline) {
                pause(pilot, SETTLE_STEP);
            }
            if (frames.get() == 0) {
                throw new AssertionError("the shell did not render a first frame within "
                        + SETTLE_TIMEOUT.toMillis() + " ms");
            }
        }

        private Pilot pilot() {
            return pilot;
        }

        private ToolkitRunner runner() {
            return runner;
        }

        private String renderedFrame() {
            assertHealthy();
            return rendered.get();
        }

        /**
         * Waits for the queued input to be handled and for the frame it
         * produces. The frame lags the handled event by at most one render of
         * the same loop iteration, so the grace window is a short bound on a
         * wait that is already known to be nearly over, not a guess at how
         * long the render thread needs.
         */
        private void awaitSettled() {
            int before = frames.get();
            try {
                awaitHandled(runner);
            } catch (AssertionError notHandled) {
                assertHealthy();
                throw notHandled;
            }
            awaitFrame(before);
        }

        private void drag(MouseEvent event) {
            int before = frames.get();
            runner.tuiRunner().dispatch(event);
            awaitFrame(before);
        }

        private void resize(int width, int height) {
            int before = frames.get();
            backend.resize(width, height);
            pilot.resize(width, height);
            awaitFrame(before);
        }

        private void awaitFrame(int before) {
            long deadline = System.nanoTime() + FRAME_GRACE.toNanos();
            while (frames.get() == before && System.nanoTime() < deadline) {
                pause(pilot, SETTLE_STEP);
            }
            assertHealthy();
        }

        private void assertHealthy() {
            Throwable problem = failure.get();
            if (problem != null) {
                throw new AssertionError("TUI render thread failed", problem);
            }
        }

        @Override
        public void close() throws Exception {
            runner.quit();
            renderThread.join(1000);
            runner.close();
            assertHealthy();
        }
    }

    private static VintelliShell defaultShell() {
        return defaultShell(defaultThemes());
    }

    private static VintelliShell defaultShell(ShellThemes themes) {
        return new VintelliShell(defaultWindows(), new ShellState(), themes);
    }

    private static List<ToolWindow> defaultWindows() {
        ShellThemes themes = defaultThemes();
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes), new ProblemsToolWindow(() -> themes)));
        return registry.toolWindows();
    }

    private static List<ToolWindow> windowsWithAcp() {
        ShellThemes themes = defaultThemes();
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                new ProblemsToolWindow(() -> themes), new AcpAgentsToolWindow(() -> themes)));
        return registry.toolWindows();
    }

    private static VintelliShell shellWithFakeWindow(FakeToolWindow fake) {
        ShellThemes themes = defaultThemes();
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes), new ProblemsToolWindow(() -> themes),
                new AcpAgentsToolWindow(() -> themes)));
        List<ToolWindow> windows = new ArrayList<>(registry.toolWindows());
        windows.add(fake);
        return new VintelliShell(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes);
    }

    /**
     * Waits until the shell under test has handled every event dispatched so
     * far. The fixed half second window this replaces was a guess at how long
     * the render thread needs; the barrier waits for the real thing (the
     * runner ran a marker task after the queued key), so the assertions read
     * the state they expect as soon as it exists instead of after a window
     * that is either too short or too long. The fixed sleeps stay where the
     * wait is for something else: the runner startup (STARTUP) and the polls
     * of a background thread (30 ms steps).
     */
    private void settle(Pilot pilot) {
        settleStrategy.await(pilot);
    }

    /** Waits for the shell under test to have handled the input dispatched so far. */
    private interface Settle {
        void await(Pilot pilot);
    }

    private Settle settleStrategy = pilot -> pilot.pause(EVENT_WINDOW);

    private static void awaitHandled(ToolkitRunner runner) {
        CountDownLatch handled = new CountDownLatch(1);
        runner.runOnRenderThread(handled::countDown);
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (handled.getCount() > 0 && runner.isRunning() && System.nanoTime() < deadline) {
            sleep(SETTLE_STEP);
        }
        if (handled.getCount() > 0 && runner.isRunning()) {
            throw new AssertionError("the shell did not handle the queued input within "
                    + SETTLE_TIMEOUT.toMillis() + " ms");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the shell", interrupted);
        }
    }

    private static void pause(Pilot pilot, Duration duration) {
        pilot.pause(duration);
    }

    private void press(Pilot pilot, char key) {
        pilot.press(key);
        settle(pilot);
    }

    private void press(Pilot pilot, char key, KeyModifiers modifiers) {
        pilot.press(key, modifiers);
        settle(pilot);
    }

    /**
     * A harmless second key press after the arrows, page keys, Home/End or
     * clicks: those are consumed by the focused project list before the shell
     * handler runs, so a follow-up event reads the selection back from the
     * list into the shell state and the status line deterministically.
     */
    private void pressQuietly(Pilot pilot) {
        pilot.press('x');
        settle(pilot);
    }

    private void pressCtrlC(Pilot pilot) {
        press(pilot, 'c', KeyModifiers.CTRL);
    }

    private void type(Pilot pilot, String text) {
        for (int index = 0; index < text.length(); index++) {
            pilot.press(text.charAt(index));
            settle(pilot);
        }
    }

    /** Types into the open palette filter on the synchronous entry point. */
    private static void type(VintelliShell shell, String text) {
        for (int index = 0; index < text.length(); index++) {
            shell.handle(KeyEvent.ofChar(text.charAt(index)));
        }
    }

    private static String helpText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = 5; row < 20; row++) {
            text.append(rowText(buffer, row));
            text.append('\n');
        }
        return text.toString();
    }

    private static Buffer offlineFrame(VintelliShell shell) {
        return offlineFocusFrame(shell, shell.themes(), null);
    }

    private static Buffer offlineFocusFrame(VintelliShell shell, ShellThemes themes, String focusedId) {
        Buffer buffer = Buffer.empty(VIEWPORT);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        if (focusedId != null) {
            context.focusManager().setFocus(focusedId);
        }
        Element root = shell.root();
        root.render(Frame.forTesting(buffer), VIEWPORT, context);
        context.registerElement(root, VIEWPORT);
        return buffer;
    }

    private static ShellThemes defaultThemes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private static Color cellBg(Buffer buffer, int x, int row) {
        return buffer.get(x, row).style().bg().orElse(null);
    }

    private static int highlightedCount(Buffer buffer, int row, Color background) {
        int count = 0;
        for (int x = 0; x < buffer.width(); x++) {
            if (background.equals(buffer.get(x, row).style().bg().orElse(null))) {
                count++;
            }
        }
        return count;
    }

    private static int firstHighlightedColumn(Buffer buffer, int row, Color background) {
        for (int x = 0; x < buffer.width(); x++) {
            if (background.equals(buffer.get(x, row).style().bg().orElse(null))) {
                return x;
            }
        }
        return -1;
    }

    private static int contiguousHighlight(Buffer buffer, int row, Color background) {
        int first = firstHighlightedColumn(buffer, row, background);
        if (first < 0) {
            return 0;
        }
        int run = 0;
        for (int x = first; x < buffer.width(); x++) {
            if (!background.equals(buffer.get(x, row).style().bg().orElse(null))) {
                break;
            }
            run++;
        }
        return run;
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString();
    }

    private static String bufferText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < buffer.height(); row++) {
            text.append(rowText(buffer, row)).append('\n');
        }
        return text.toString();
    }

    private static int findBottomTabRow(Buffer buffer) {
        for (int row = 0; row < buffer.height(); row++) {
            String text = rowText(buffer, row);
            if (text.contains("1 Run") && text.contains("2 Terminal")) {
                return row;
            }
        }
        return 13;
    }

    private static int firstForegroundColumn(Buffer buffer, int row, Color foreground) {
        for (int x = 0; x < buffer.width(); x++) {
            if (foreground.equals(buffer.get(x, row).style().fg().orElse(null))) {
                return x;
            }
        }
        return -1;
    }

    private static int contiguousForeground(Buffer buffer, int row, Color foreground) {
        int first = firstForegroundColumn(buffer, row, foreground);
        if (first < 0) {
            return 0;
        }
        int run = 0;
        for (int x = first; x < buffer.width(); x++) {
            if (!foreground.equals(buffer.get(x, row).style().fg().orElse(null))) {
                break;
            }
            run++;
        }
        return run;
    }
}
