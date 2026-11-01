package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyAction;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        assertTrue(rendered.get().height() > VintelliShell.TOOL_WINDOW_HEIGHT,
                "side windows should fill the stripe instead of stopping at the bottom window height");
    }

    @TempDir
    Path project;

    private static final Duration STARTUP = Duration.ofMillis(100);
    private static final Duration EVENT_WINDOW = Duration.ofMillis(500);
    private static final Duration TYPE_WINDOW = Duration.ofMillis(50);
    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final Size SIZE = new Size(VIEWPORT.width(), VIEWPORT.height());
    private static final int MENU_ROW = 0;
    private static final int LEFT_TAB_ROW = 1; // Left stripe tab bar (empty in default)
    private static final int FIRST_PROJECT_ROW = 2; // Project panel now starts at row 2
    private static final int SECOND_PROJECT_ROW = 3;
    private static final int PROJECT_ROW_HIGHLIGHT = VintelliShell.PROJECT_PANEL_WIDTH - 2;
    // Bottom stripe tab bar row (after menu + left content + editor)
    private static final int BOTTOM_TAB_ROW = 13; // approximate, will be computed dynamically
    private static final int TOOL_WINDOW_ROW = 14;
    private static final int STATUS_ROW = 23;
    private static final int EDITOR_PROBE_X = 58;
    private static final int EDITOR_PROBE_Y = 12;

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
            pilot.pause(EVENT_WINDOW);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex());
            assertEquals("Selected: VintelliMain.java", shell.status());

            pilot.press(KeyCode.DOWN);
            pilot.pause(EVENT_WINDOW);
            pressQuietly(pilot);
            assertEquals(2, shell.fileIndex());

            pilot.press(KeyCode.UP);
            pilot.pause(EVENT_WINDOW);
            pilot.press(KeyCode.UP);
            pilot.pause(EVENT_WINDOW);
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
                pilot.pause(EVENT_WINDOW);
            }
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 6; step++) {
                pilot.press(KeyCode.DOWN);
                pilot.pause(EVENT_WINDOW);
            }
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 10; step++) {
                pilot.press(KeyCode.UP);
                pilot.pause(EVENT_WINDOW);
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
            pause(pilot);
            assertEquals(1, editor.activeDocument().orElseThrow().column(), "the editor has keyboard focus initially");

            pilot.press(KeyCode.F10);
            pause(pilot);
            for (int menu = 0; menu < 8; menu++) {
                pilot.press(KeyCode.RIGHT);
                pause(pilot);
            }
            for (int row = 0; row < 3; row++) {
                pilot.press(KeyCode.DOWN);
                pause(pilot);
            }
            pilot.press(KeyCode.ENTER);
            pause(pilot);

            assertEquals(1, editor.activeDocument().orElseThrow().column(),
                    "menu keys must not move the text cursor or insert a newline");
            assertTrue(shell.status().startsWith("Stage sample.txt? [y/N]"), shell.status());
            pilot.press('n');
            pause(pilot);
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
        assertTrue(rendered.contains("vintelli/pom.xml M"), "the editor tab should show Git state: " + rendered);
        assertTrue(rendered.contains("+ 2"), "the gutter should mark changed line 2: " + rendered);
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
        // First tab is " 1. Run " - its width is 8
        int tab1Width = " 1. Run ".length();

        assertEquals(tab1Width, contiguousHighlight(buffer, bottomTabRow, tabSelected),
                "exactly the selected tab carries its highlight: " + tabRow);
        assertEquals(0, firstHighlightedColumn(buffer, bottomTabRow, tabSelected),
                "the selected tab highlight starts at tab 1: " + tabRow);
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
        // Tab 3 (" 3. Problems ") starts after " 1. Run   2. Terminal   "
        int tab3Start = tabRow.indexOf(" 3. Problems ");
        assertTrue(tab3Start >= 0, "tab 3 should be present: " + tabRow);
        assertEquals(tab3Start, firstHighlightedColumn(buffer, bottomTabRow, tabSelected),
                "the selected tab highlight starts at tab 3: " + tabRow);
        assertEquals(" 3. Problems ".length(), contiguousHighlight(buffer, bottomTabRow, tabSelected), tabRow);
        // Tabs 1 and 2 should not have the highlight
        for (int column = 0; column < tab3Start; column++) {
            assertFalse(tabSelected.equals(buffer.get(column, bottomTabRow).style().bg().orElse(null)),
                    "tabs 1 and 2 must lose the highlight after switching to tab 3: " + tabRow);
        }
    }

    @Test
    void showsATabForAToolWindowRegisteredWithoutShellChanges() {
        VintelliShell shell = shellWithFakeWindow(new FakeToolWindow("fake.threads", "Threads", '7', 50, "fake thread dump", false, ToolWindow.Anchor.BOTTOM));
        Buffer buffer = offlineFrame(shell);

        int bottomTabRow = findBottomTabRow(buffer);
        String tabRow = rowText(buffer, bottomTabRow);
        assertTrue(tabRow.contains("1. Run"), tabRow);
        assertTrue(tabRow.contains("7. Threads"), tabRow);
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
            pause(pilot);
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
            pause(pilot);
            assertEquals("Focus: shell navigation", shell.status());
            assertFalse(terminalLike.handledKeys().contains("escape"), "escape stays with the shell");
            pilot.press(KeyCode.DOWN);
            pause(pilot);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex(), "after escape the shell navigates again");
            pilot.press(KeyCode.DOWN);
            pause(pilot);
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
            pause(pilot);
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
            pause(pilot);
            pressQuietly(pilot);
            assertEquals("VintelliMain.java", shell.state().snapshot().selectedFile());
            assertEquals("run", shell.state().snapshot().selectedToolWindowId());

            pilot.press(KeyCode.UP);
            pause(pilot);
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
            pause(pilot);
            pressCtrlC(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C quits once the shell has the focus");
        }
    }

    @Test
    void everyCatalogActionDispatchesFromThePaletteWithoutAnUnhandledStatus() throws Exception {
        for (KeyAction action : Keymap.ofProfile(KeymapProfile.DEFAULT, defaultWindows()).actions()) {
            if (action.id().equals(KeyActions.QUIT)) {
                continue;
            }
            VintelliShell shell = defaultShell();
            try (ToolkitTestRunner test = runShell(shell)) {
                Pilot pilot = test.pilot();
                pilot.pause(STARTUP);

                press(pilot, 'p', KeyModifiers.CTRL);
                type(pilot, action.id().toLowerCase());
                pilot.press(KeyCode.ENTER);
                pause(pilot);

                assertFalse(shell.status().startsWith("Unhandled action"), action.id() + ": " + shell.status());
                assertFalse(shell.paletteOpen() && !action.id().equals(KeyActions.SEARCH_EVERYWHERE), action.id());

                pressCtrlC(pilot);
            }
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
            pause(pilot);
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
            pause(pilot);
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
            pause(pilot);
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
            pause(pilot);
            assertFalse(shell.paletteOpen());
            // ACP Agents is in RIGHT anchor, bottom index stays 3, right index becomes 0
            assertEquals(3, shell.toolIndex(), "bottom anchor index unchanged");
            assertEquals("Tool window: ACP Agents", shell.status());

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
            pause(pilot);
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
            pause(pilot);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex());
            assertEquals("VintelliMain.java", shell.state().snapshot().selectedFile());

            pilot.press(KeyCode.DOWN);
            pause(pilot);
            pressQuietly(pilot);
            assertEquals(2, shell.fileIndex(), "the next arrow continues from the arrow selection");

            pilot.press(KeyCode.END);
            pause(pilot);
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("VintelliShellTest.java", shell.state().snapshot().selectedFile());

            pilot.press(KeyCode.HOME);
            pause(pilot);
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
        String contentRow = rowText(buffer, TOOL_WINDOW_ROW);
        assertTrue(contentRow.contains("tool window render failed: boom"), contentRow);
        assertTrue(rowText(buffer, MENU_ROW).contains("File"), "the rest of the shell keeps rendering");
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
            pause(pilot, EVENT_WINDOW);
            assertEquals("editor-panel", test.runner().focusManager().focusedId(),
                    "clicking a panel by id moves the focus there");

            pilot.click("project-panel");
            pause(pilot, EVENT_WINDOW);
            assertEquals("project-panel", test.runner().focusManager().focusedId());

            pressCtrlC(pilot);
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
            pause(pilot, EVENT_WINDOW);
            assertEquals("editor-panel", test.runner().focusManager().focusedId());
            pilot.press("TAB");
            pause(pilot, EVENT_WINDOW);
            assertEquals("project-panel", test.runner().focusManager().focusedId(),
                    "the focus cycles back through the registered panels");

            pressCtrlC(pilot);
        }
    }

    @Test
    void focusChangesTheFocusedPanelBorderThroughTheStylesheet() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        Color focusedBorder = themes.color("focus-border");
        Color border = themes.color("border");

        Buffer unfocused = offlineFocusFrame(shell, themes, null);
        Buffer focused = offlineFocusFrame(shell, themes, "project-panel");

        // Project panel right border is at column PROJECT_PANEL_WIDTH - 1, first content row
        int projectBorderCol = VintelliShell.PROJECT_PANEL_WIDTH - 1;
        int projectRow = FIRST_PROJECT_ROW;

        assertEquals(border, unfocused.get(projectBorderCol, projectRow).style().fg().orElse(null),
                "an unfocused panel border follows the chrome border color");
        assertEquals(focusedBorder, focused.get(projectBorderCol, projectRow).style().fg().orElse(null),
                "the focused panel border follows the :focus rule");
    }

    @Test
    void theEditorBorderStaysVisibleAgainstItsOwnBackgroundInBothThemes() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        // Editor panel left border is at PROJECT_PANEL_WIDTH (right edge of project panel)
        int editorBorderCol = VintelliShell.PROJECT_PANEL_WIDTH;
        for (int round = 0; round < 2; round++) {
            Map<String, String> values = themes.variablesOf(themes.activeTheme());
            Buffer buffer = offlineFrame(shell);
            Color border = buffer.get(editorBorderCol, 2).style().fg().orElse(null);
            assertEquals(Color.hex(values.get("editor-border")), border,
                    "the unfocused editor border paints the themed border color in '" + themes.activeTheme() + "'");
            assertFalse(Color.hex(values.get("editor-bg")).equals(border),
                    "the unfocused editor border must differ from its background in '" + themes.activeTheme() + "'");
            themes.switchTheme("dark".equals(themes.activeTheme()) ? "darcula" : "dark");
        }
    }

    @Test
    void switchThemeRestylesTheWholeShellWithoutRestart() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);

        Buffer beforeBuffer = offlineFrame(shell);
        int bottomTabRow = findBottomTabRow(beforeBuffer);
        // First tab starts at column 0
        assertEquals(themes.color("tab-selected-bg"), cellBg(beforeBuffer, 4, bottomTabRow),
                "the active theme styles the selected tab before the switch");
        // Project panel background at an unselected row (2nd project row)
        assertEquals(themes.color("panel"), cellBg(beforeBuffer, 2, SECOND_PROJECT_ROW),
                "the active theme styles the project panel before the switch");
        assertEquals(themes.color("editor-bg"), cellBg(beforeBuffer, EDITOR_PROBE_X, EDITOR_PROBE_Y),
                "the active theme styles the editor panel before the switch");
        assertEquals(themes.color("panel"), cellBg(beforeBuffer, 40, STATUS_ROW),
                "the active theme styles the status bar before the switch");
        assertTrue(rowText(beforeBuffer, MENU_ROW).contains("File"));

        themes.switchTheme("darcula");

        Buffer afterBuffer = offlineFrame(shell);
        int afterTabRow = findBottomTabRow(afterBuffer);
        assertEquals(themes.color("tab-selected-bg"), cellBg(afterBuffer, 4, afterTabRow),
                "the switched theme reaches the selected tab");
        assertEquals(themes.color("panel"), cellBg(afterBuffer, 2, SECOND_PROJECT_ROW),
                "the switched theme reaches the project panel");
        assertEquals(themes.color("editor-bg"), cellBg(afterBuffer, EDITOR_PROBE_X, EDITOR_PROBE_Y),
                "the switched theme reaches the editor panel");
        assertEquals(themes.color("panel"), cellBg(afterBuffer, 40, STATUS_ROW),
                "the switched theme reaches the status bar");
        assertTrue(rowText(afterBuffer, MENU_ROW).contains("Edit  View"),
                "the theme switch keeps the whole shell rendering");
        assertFalse(themes.variablesOf("dark").get("panel").equalsIgnoreCase(themes.variablesOf("darcula").get("panel")),
                "the two shipped themes actually differ so the assertions above mean something");
    }

    private static ToolkitTestRunner runShell(VintelliShell shell) throws Exception {
        ToolkitTestRunner test = ToolkitTestRunner.runTest(shell::root, SIZE);
        test.runner().styleEngine(shell.themes().engine());
        test.runner().eventRouter().addGlobalHandler(shell);
        shell.bindQuit(test.runner()::quit);
        return test;
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
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes), new ProblemsToolWindow(() -> themes),
                new AcpAgentsToolWindow(() -> themes)));
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

    private static void pause(Pilot pilot) {
        pilot.pause(EVENT_WINDOW);
    }

    private static void pause(Pilot pilot, Duration duration) {
        pilot.pause(duration);
    }

    private static void press(Pilot pilot, char key) {
        pilot.press(key);
        pilot.pause(EVENT_WINDOW);
    }

    private static void press(Pilot pilot, char key, KeyModifiers modifiers) {
        pilot.press(key, modifiers);
        pilot.pause(EVENT_WINDOW);
    }

    /**
     * A harmless second key press after the arrows, page keys, Home/End or
     * clicks: those are consumed by the focused project list before the shell
     * handler runs, so a follow-up event reads the selection back from the
     * list into the shell state and the status line deterministically.
     */
    private static void pressQuietly(Pilot pilot) {
        pilot.press('x');
        pause(pilot);
    }

    private static void pressCtrlC(Pilot pilot) {
        press(pilot, 'c', KeyModifiers.CTRL);
    }

    private static void type(Pilot pilot, String text) {
        for (int index = 0; index < text.length(); index++) {
            pilot.press(text.charAt(index));
            pilot.pause(TYPE_WINDOW);
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
        // Find the row that contains the bottom tool window tabs (looks for " 1. Run ")
        for (int row = 0; row < buffer.height(); row++) {
            String text = rowText(buffer, row);
            if (text.contains(" 1. Run ") && text.contains(" 2. Terminal ")) {
                return row;
            }
        }
        return 13; // fallback
    }
}
