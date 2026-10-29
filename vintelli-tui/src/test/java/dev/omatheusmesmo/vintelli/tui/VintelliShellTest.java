package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyAction;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VintelliShellTest {

    private static final Duration STARTUP = Duration.ofMillis(100);
    private static final Duration EVENT_WINDOW = Duration.ofMillis(500);
    private static final Duration TYPE_WINDOW = Duration.ofMillis(50);
    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final int MENU_ROW = 0;
    private static final int TAB_ROW = 1;
    private static final int FIRST_PROJECT_ROW = 3;
    private static final int SECOND_PROJECT_ROW = 4;
    private static final int FIRST_TAB_WIDTH = " 1. Run ".length();
    private static final int TABS_UNHIGHLIGHTED_ZONE = FIRST_TAB_WIDTH + 1 + (" 2. Terminal ".length() - 1);
    private static final int TAB_SWITCH_HIGHLIGHT = " 3. Problems ".length();
    private static final int PROJECT_ROW_HIGHLIGHT = 34 - 2;
    private static final int TOOL_WINDOW_ROW = 14;
    private static final int EDITOR_PROBE_X = 60;
    private static final int EDITOR_PROBE_Y = 12;
    private static final Color SELECTION = Color.hex("#365880");
    private static final Color BRIGHT = Color.hex("#ffffff");
    private static final Color BACKGROUND = Color.hex("#2b2b2b");

    @Test
    void selectsToolWindowsByDigitKeys() throws Exception {
        VintelliShell shell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '2');
            assertEquals(1, shell.toolIndex());
            assertEquals("Tool window: Terminal", shell.status());

            press(pilot, '4');
            assertEquals(3, shell.toolIndex());
            assertEquals("Tool window: ACP Agents", shell.status());

            press(pilot, '1');
            assertEquals(0, shell.toolIndex());
            assertEquals("Tool window: Run", shell.status());

            press(pilot, 'x');
            assertEquals(0, shell.toolIndex());
            assertEquals("Tool window: Run", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void navigatesProjectTreeWithArrowKeysInTheDefaultProfile() throws Exception {
        VintelliShell shell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.DOWN);
            pilot.pause(EVENT_WINDOW);
            assertEquals(1, shell.fileIndex());
            assertEquals("Selected: VintelliMain.java", shell.status());

            pilot.press(KeyCode.DOWN);
            pilot.pause(EVENT_WINDOW);
            assertEquals(2, shell.fileIndex());

            pilot.press(KeyCode.UP);
            pilot.pause(EVENT_WINDOW);
            pilot.press(KeyCode.UP);
            pilot.pause(EVENT_WINDOW);
            assertEquals(0, shell.fileIndex());
            assertEquals("Selected: vintelli/pom.xml", shell.status());

            assertTrue(test.runner().isRunning());

            pressCtrlC(pilot);
        }
    }

    @Test
    void clampsSelectionAtListBounds() throws Exception {
        VintelliShell shell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            for (int step = 0; step < 4; step++) {
                pilot.press(KeyCode.DOWN);
                pilot.pause(EVENT_WINDOW);
            }
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 6; step++) {
                pilot.press(KeyCode.DOWN);
                pilot.pause(EVENT_WINDOW);
            }
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 10; step++) {
                pilot.press(KeyCode.UP);
                pilot.pause(EVENT_WINDOW);
            }
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
    void highlightsOnlySelectedTabAndRowOnFirstFrame() {
        VintelliShell shell = defaultShell();
        Buffer buffer = offlineFrame(shell);

        assertEquals(FIRST_TAB_WIDTH, highlightedCount(buffer, TAB_ROW, SELECTION),
                rowText(buffer, TAB_ROW));
        assertEquals(PROJECT_ROW_HIGHLIGHT, highlightedCount(buffer, FIRST_PROJECT_ROW, BRIGHT),
                rowText(buffer, FIRST_PROJECT_ROW));
        assertEquals(0, highlightedCount(buffer, SECOND_PROJECT_ROW, BRIGHT),
                "unselected project rows must not carry the selection color");
        assertEquals(BACKGROUND, buffer.get(EDITOR_PROBE_X, EDITOR_PROBE_Y).style().bg().orElse(null),
                "the root background must survive widgets drawn on top of it");
    }

    @Test
    void movesHighlightToTab3AndDropsTab1AfterDigitPress() throws Exception {
        VintelliShell shell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            press(pilot, '3');
            assertEquals(2, shell.toolIndex());
            pressCtrlC(pilot);
        }

        Buffer buffer = offlineFrame(shell);
        assertEquals(TAB_SWITCH_HIGHLIGHT, highlightedCount(buffer, TAB_ROW, SELECTION),
                rowText(buffer, TAB_ROW));
        assertTrue(highlightedColumnsBefore(buffer, TAB_ROW, SELECTION, TABS_UNHIGHLIGHTED_ZONE) == 0,
                "tabs 1 and 2 must lose the highlight after switching to tab 3: " + rowText(buffer, TAB_ROW));
    }

    @Test
    void showsATabForAToolWindowRegisteredWithoutShellChanges() {
        VintelliShell shell = shellWithFakeWindow(new FakeToolWindow("fake.threads", "Threads", '7', 50, "fake thread dump"));
        Buffer buffer = offlineFrame(shell);

        String tabRow = rowText(buffer, TAB_ROW);
        assertTrue(tabRow.contains("1. Run"), tabRow);
        assertTrue(tabRow.contains("7. Threads"), tabRow);
    }

    @Test
    void selectsATabByItsDeclaredShortcutAndRecordsItInState() throws Exception {
        VintelliShell shell = shellWithFakeWindow(new FakeToolWindow("fake.threads", "Threads", '7', 50, "fake thread dump"));
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '7');
            assertEquals("fake.threads", shell.state().snapshot().selectedToolWindowId());
            assertEquals("Tool window: Threads", shell.status());
            assertEquals(4, shell.toolIndex());

            pressCtrlC(pilot);
        }
    }

    @Test
    void routesUnclaimedKeysToTheActiveToolWindowBeforeShellShortcuts() throws Exception {
        FakeToolWindow fake = new FakeToolWindow("fake.notes", "Notes", '6', 60, "fake notes");
        VintelliShell shell = shellWithFakeWindow(fake);
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            press(pilot, 'j');
            assertEquals(0, shell.fileIndex(), "the active tool window must consume j before the project list");
            assertTrue(fake.handledKeys().contains("j"), fake.handledKeys().toString());

            pressCtrlC(pilot);
        }
    }

    @Test
    void capturingActiveWindowReceivesEvenTheShellKeys() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            press(pilot, 'q');
            press(pilot, 'j');
            press(pilot, '1');
            assertEquals(0, shell.fileIndex(), "captured input must not drive the shell");
            assertEquals(4, shell.toolIndex(), "captured input must not switch tabs");
            assertTrue(terminalLike.handledKeys().containsAll(List.of("q", "j", "1")),
                    terminalLike.handledKeys().toString());

            assertTrue(test.runner().isRunning(), "the capturing window keeps q for text, not quit");

            press(pilot, '4', KeyModifiers.ALT);
            assertEquals(3, shell.toolIndex());
            assertEquals("Tool window: ACP Agents", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void switchesToolWindowsByActionChordWhileAnInputHasFocus() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            press(pilot, '2');
            assertEquals(4, shell.toolIndex(), "a bare digit belongs to the capturing input");
            assertEquals(0, shell.fileIndex(), "a bare digit must not drive the project list");
            assertTrue(terminalLike.handledKeys().contains("2"), terminalLike.handledKeys().toString());

            press(pilot, '3', KeyModifiers.ALT);
            assertEquals(2, shell.toolIndex(), "the activate action chord must survive captured input");
            assertEquals("Tool window: Problems", shell.status());

            press(pilot, '6', KeyModifiers.ALT);
            assertEquals(4, shell.toolIndex());

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
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
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
            assertEquals(1, shell.fileIndex(), "after escape the shell navigates again");
            pilot.press(KeyCode.DOWN);
            pause(pilot);
            assertEquals(2, shell.fileIndex(), "the capturing window must stay out of the shell keys");

            pressCtrlC(pilot);
        }
    }

    @Test
    void selectsTheCapturingWindowAgainThroughItsShortcutAfterEscape() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
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
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.DOWN);
            pause(pilot);
            assertEquals("VintelliMain.java", shell.state().snapshot().selectedFile());
            assertEquals("run", shell.state().snapshot().selectedToolWindowId());

            pilot.press(KeyCode.UP);
            pause(pilot);
            assertEquals("vintelli/pom.xml", shell.state().snapshot().selectedFile());

            pressCtrlC(pilot);
        }
    }

    @Test
    void rendersTheFallbackTextWhenNoToolWindowIsRegistered() {
        VintelliShell shell = new VintelliShell(List.of(), new ShellState());
        Buffer buffer = offlineFrame(shell);

        String contentRow = rowText(buffer, TOOL_WINDOW_ROW);
        assertTrue(contentRow.contains("no tool window beans registered"), contentRow);
    }

    @Test
    void theKeymapIsBuiltOnFirstRenderNotWhenTheShellIsConstructedOrRead() {
        AtomicInteger builds = new AtomicInteger();
        VintelliShell shell = new VintelliShell(defaultWindows(), new ShellState(), () -> {
            builds.incrementAndGet();
            return Keymap.ofProfile(KeymapProfile.DEFAULT, defaultWindows());
        });

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
        });

        assertEquals(0, shell.fileIndex());
        assertThrows(IllegalStateException.class, () -> offlineFrame(shell));
    }

    @Test
    void ctrlCQuitsTheShellAndBareQDoesNot() throws Exception {
        VintelliShell shell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            String initialStatus = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
            press(pilot, 'q');
            assertTrue(test.runner().isRunning(), "the intellij profile types instead of quitting");
            assertEquals(initialStatus, shell.status(), "q must not drive shell navigation either");

            pressCtrlC(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C must stop the shell event loop");
        }
    }

    @Test
    void ctrlCQuitsTheShellFromTheShellAndGoesToTheInputWhileCapturing() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '6', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
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
            try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
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
    void paletteShowsTheActiveChordAndTheIntelliJShortcutPerAction() throws Exception {
        VintelliShell shell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'p', KeyModifiers.CTRL);
            type(pilot, "search ev");
            pause(pilot);

            Buffer buffer = offlineFrame(shell);
            String palette = helpText(buffer);
            assertTrue(palette.contains("Search Everywhere"), palette);
            assertTrue(palette.contains("Ctrl+p"), palette);
            assertTrue(palette.contains("Double Shift"), palette);

            pressCtrlC(pilot);
        }
    }

    @Test
    void vimProfileNavigatesTheProjectListWithJAndK() throws Exception {
        VintelliShell vimShell = profileShell(KeymapProfile.VIM);
        try (TuiTestRunner test = TuiTestRunner.runTest(vimShell, vimShell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'j');
            assertEquals(1, vimShell.fileIndex(), "the vim profile must navigate the project list with j");
            press(pilot, 'k');
            assertEquals(0, vimShell.fileIndex(), "the vim profile must navigate the project list with k");

            pressCtrlC(pilot);
        }
    }

    @Test
    void defaultProfileNavigatesOnlyWithArrowKeys() throws Exception {
        VintelliShell intellijShell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(intellijShell, intellijShell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'j');
            assertEquals(0, intellijShell.fileIndex(), "the intellij profile navigates with arrows only");
            press(pilot, 'k');
            assertEquals(0, intellijShell.fileIndex(), "the intellij profile navigates with arrows only");
            pilot.press(KeyCode.DOWN);
            pause(pilot);
            assertEquals(1, intellijShell.fileIndex());

            pressCtrlC(pilot);
        }
    }

    @Test
    void commandPaletteOpensThroughTheKeymapChordAndNeverQuitsOnQ() throws Exception {
        VintelliShell shell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
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
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '6');
            press(pilot, 'p', KeyModifiers.CTRL);
            assertTrue(shell.paletteOpen(), "search everywhere stays reachable while an input has focus");
            assertEquals(4, shell.toolIndex(), "opening the palette must release the captured input");

            type(pilot, "acp");
            pilot.press(KeyCode.ENTER);
            pause(pilot);
            assertFalse(shell.paletteOpen());
            assertEquals(3, shell.toolIndex(), "the palette must dispatch the picked activation action");
            assertEquals("Tool window: ACP Agents", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void keymapHelpOverlayListsTheActiveKeymap() throws Exception {
        VintelliShell shell = defaultShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.F1);
            pause(pilot);
            assertTrue(shell.helpOverlayOpen(), "F1 must open the keymap help overlay");
            assertEquals("Keymap: intellij", shell.status());

            Buffer buffer = offlineFrame(shell);
            assertTrue(rowText(buffer, 5).contains("Keymap: intellij"), rowText(buffer, 5));
            String help = helpText(buffer);
            assertTrue(help.contains("Activate Run Tool Window"), help);
            assertTrue(help.contains("Alt+1"), help);
            assertTrue(help.contains("Search Everywhere"), help);
            assertTrue(help.contains("Ctrl+p"), help);

            pilot.press(KeyCode.ESCAPE);
            pause(pilot);
            assertFalse(shell.helpOverlayOpen(), "escape closes the help overlay");

            pressCtrlC(pilot);
        }
    }

    private static List<ToolWindow> defaultWindows() {
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(), new TerminalToolWindow(), new ProblemsToolWindow(), new AcpAgentsToolWindow()));
        return registry.toolWindows();
    }

    private static VintelliShell defaultShell() {
        return new VintelliShell(defaultWindows(), new ShellState());
    }

    private static VintelliShell profileShell(KeymapProfile profile) {
        return new VintelliShell(defaultWindows(), new ShellState(), Keymap.ofProfile(profile, defaultWindows()));
    }

    private static VintelliShell shellWithFakeWindow(FakeToolWindow fake) {
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(), new TerminalToolWindow(), new ProblemsToolWindow(), new AcpAgentsToolWindow()));
        List<ToolWindow> windows = new ArrayList<>(registry.toolWindows());
        windows.add(fake);
        return new VintelliShell(new ToolWindowRegistry(windows).toolWindows(), new ShellState());
    }

    private static void pause(Pilot pilot) {
        pilot.pause(EVENT_WINDOW);
    }

    private static void press(Pilot pilot, char key) {
        pilot.press(key);
        pilot.pause(EVENT_WINDOW);
    }

    private static void press(Pilot pilot, char key, KeyModifiers modifiers) {
        pilot.press(key, modifiers);
        pilot.pause(EVENT_WINDOW);
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
        for (int row = 6; row < 20; row++) {
            text.append(rowText(buffer, row));
            text.append('\n');
        }
        return text.toString();
    }

    private static Buffer offlineFrame(VintelliShell shell) {
        Buffer buffer = Buffer.empty(VIEWPORT);
        shell.render(Frame.forTesting(buffer));
        return buffer;
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString();
    }

    private static List<Cell> highlightedRowCells(Buffer buffer, int row, Color background) {
        List<Cell> matching = new ArrayList<>();
        for (int x = 0; x < buffer.width(); x++) {
            Cell cell = buffer.get(x, row);
            if (background.equals(cell.style().bg().orElse(null))) {
                matching.add(cell);
            }
        }
        return matching;
    }

    private static int highlightedCount(Buffer buffer, int row, Color background) {
        return highlightedRowCells(buffer, row, background).size();
    }

    private static int highlightedColumnsBefore(Buffer buffer, int row, Color background, int exclusiveMaxColumn) {
        int count = 0;
        for (int x = 0; x < exclusiveMaxColumn; x++) {
            if (background.equals(buffer.get(x, row).style().bg().orElse(null))) {
                count++;
            }
        }
        return count;
    }
}
