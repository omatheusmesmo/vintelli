package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VintelliShellTest {

    private static final Duration STARTUP = Duration.ofMillis(100);
    private static final Duration EVENT_WINDOW = Duration.ofMillis(500);
    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final int MENU_ROW = 0;
    private static final int TAB_ROW = 1;
    private static final int FIRST_PROJECT_ROW = 3;
    private static final int SECOND_PROJECT_ROW = 4;
    private static final int FIRST_TAB_WIDTH = " 1. Run ".length();
    private static final int TABS_UNHIGHLIGHTED_ZONE = FIRST_TAB_WIDTH + 1 + (" 2. Terminal ".length() - 1);
    private static final int TAB_SWITCH_HIGHLIGHT = " 3. Problems ".length();
    private static final int PROJECT_ROW_HIGHLIGHT = 34 - 2;
    private static final int EDITOR_PROBE_X = 60;
    private static final int EDITOR_PROBE_Y = 12;
    private static final Color SELECTION = Color.hex("#365880");
    private static final Color BRIGHT = Color.hex("#ffffff");
    private static final Color BACKGROUND = Color.hex("#2b2b2b");

    @Test
    void selectsToolWindowsByDigitKeys() throws Exception {
        VintelliShell shell = new VintelliShell();
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

            press(pilot, 'q');
        }
    }

    @Test
    void navigatesProjectTreeWithJAndK() throws Exception {
        VintelliShell shell = new VintelliShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'j');
            assertEquals(1, shell.fileIndex());
            assertEquals("Selected: VintelliMain.java", shell.status());

            press(pilot, 'j');
            assertEquals(2, shell.fileIndex());

            press(pilot, 'k');
            press(pilot, 'k');
            assertEquals(0, shell.fileIndex());
            assertEquals("Selected: vintelli/pom.xml", shell.status());

            press(pilot, 'q');
        }
    }

    @Test
    void clampsSelectionAtListBounds() throws Exception {
        VintelliShell shell = new VintelliShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            for (int step = 0; step < 4; step++) {
                press(pilot, 'j');
            }
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 6; step++) {
                press(pilot, 'j');
            }
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 10; step++) {
                press(pilot, 'k');
            }
            assertEquals(0, shell.fileIndex());
            assertEquals("Selected: vintelli/pom.xml", shell.status());

            press(pilot, 'q');
        }
    }

    @Test
    void separatesMenuEntriesOnFirstRow() {
        VintelliShell shell = new VintelliShell();
        Buffer buffer = offlineFrame(shell);

        String menu = rowText(buffer, MENU_ROW);
        assertTrue(menu.contains("Edit  View"), menu);
        assertTrue(menu.contains("Tools  VCS"), menu);
        assertTrue(menu.contains("Code  Refactor"), menu);
    }

    @Test
    void highlightsOnlySelectedTabAndRowOnFirstFrame() {
        VintelliShell shell = new VintelliShell();
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
        VintelliShell shell = new VintelliShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            press(pilot, '3');
            assertEquals(2, shell.toolIndex());
            press(pilot, 'q');
        }

        Buffer buffer = offlineFrame(shell);
        assertEquals(TAB_SWITCH_HIGHLIGHT, highlightedCount(buffer, TAB_ROW, SELECTION),
                rowText(buffer, TAB_ROW));
        assertTrue(highlightedColumnsBefore(buffer, TAB_ROW, SELECTION, TABS_UNHIGHLIGHTED_ZONE) == 0,
                "tabs 1 and 2 must lose the highlight after switching to tab 3: " + rowText(buffer, TAB_ROW));
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

    private static void press(Pilot pilot, char key) {
        pilot.press(key);
        pilot.pause(EVENT_WINDOW);
    }
}
