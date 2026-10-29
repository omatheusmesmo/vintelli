package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VintelliShellTest {

    @Test
    void selectsToolWindowsByDigitKeys() throws Exception {
        VintelliShell shell = new VintelliShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();

            pilot.press('2');
            pilot.pause();
            assertEquals(1, shell.toolIndex());

            pilot.press('4');
            pilot.pause();
            assertEquals(3, shell.toolIndex());

            pilot.press('1');
            pilot.pause();
            assertEquals(0, shell.toolIndex());

            pilot.press('x');
            pilot.pause();
            assertEquals(0, shell.toolIndex());

            pilot.press('q');
            pilot.pause();
        }
    }

    @Test
    void navigatesProjectTreeWithJAndK() throws Exception {
        VintelliShell shell = new VintelliShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();

            pilot.press('j');
            pilot.pause();
            assertEquals(1, shell.fileIndex());

            pilot.press('j');
            pilot.pause();
            assertEquals(2, shell.fileIndex());

            pilot.press('k');
            pilot.pause();
            assertEquals(1, shell.fileIndex());

            pilot.press('q');
            pilot.pause();
        }
    }
}
