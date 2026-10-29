package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VintelliShellTest {

    private static final Duration STARTUP = Duration.ofMillis(100);
    private static final Duration EVENT_WINDOW = Duration.ofMillis(500);

    private Pilot pilot(TuiTestRunner test) {
        return test.pilot();
    }

    private void press(Pilot pilot, char key) {
        pilot.press(key);
        pilot.pause(EVENT_WINDOW);
    }

    @Test
    void selectsToolWindowsByDigitKeys() throws Exception {
        VintelliShell shell = new VintelliShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = pilot(test);
            pilot.pause(STARTUP);

            press(pilot, '2');
            assertEquals(1, shell.toolIndex());

            press(pilot, '4');
            assertEquals(3, shell.toolIndex());

            press(pilot, '1');
            assertEquals(0, shell.toolIndex());

            press(pilot, 'x');
            assertEquals(0, shell.toolIndex());

            press(pilot, 'q');
        }
    }

    @Test
    void navigatesProjectTreeWithJAndK() throws Exception {
        VintelliShell shell = new VintelliShell();
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = pilot(test);
            pilot.pause(STARTUP);

            press(pilot, 'j');
            assertEquals(1, shell.fileIndex());

            press(pilot, 'j');
            assertEquals(2, shell.fileIndex());

            press(pilot, 'k');
            assertEquals(1, shell.fileIndex());

            press(pilot, 'q');
        }
    }
}
