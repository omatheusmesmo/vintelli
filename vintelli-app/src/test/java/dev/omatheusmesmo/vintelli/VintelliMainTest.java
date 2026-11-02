package dev.omatheusmesmo.vintelli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class VintelliMainTest {

    @Test
    void tuiFailureWithoutFileLoggingIsTheOneLineMessage() {
        assertEquals("Vintelli cannot start the TUI: bad theme",
                VintelliMain.tuiFailure("bad theme", false, Optional.of("/state/vintelli.log")));
    }

    @Test
    void tuiFailureWithFileLoggingPointsAtTheLogFile() {
        String message = VintelliMain.tuiFailure("bad theme", true, Optional.of("/state/vintelli.log"));
        assertTrue(message.startsWith("Vintelli cannot start the TUI: bad theme"), message);
        assertTrue(message.contains("/state/vintelli.log"), message);
    }

    @Test
    void tuiFailureNeverPrintsAnUnknownLogFile() {
        assertFalse(VintelliMain.tuiFailure("bad theme", true, Optional.empty()).contains("log file"));
    }
}
