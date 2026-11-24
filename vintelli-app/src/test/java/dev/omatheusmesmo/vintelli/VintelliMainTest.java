package dev.omatheusmesmo.vintelli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyboardMode;
import dev.tamboui.terminal.KeyboardProtocol;
import org.junit.jupiter.api.Test;

class VintelliMainTest {

    @Test
    void selectsVintelliJLine3ByDefaultWhenNoBackendIsConfigured() {
        Map<String, String> properties = new HashMap<>();
        VintelliMain.selectDefaultBackend(properties::get, ignored -> null, properties::put);
        assertEquals("vintelli-jline3", properties.get(VintelliMain.BACKEND_PROPERTY));
    }

    @Test
    void keepsConfiguredSystemPropertyBackend() {
        Map<String, String> properties = new HashMap<>();
        properties.put(VintelliMain.BACKEND_PROPERTY, "jline3");
        VintelliMain.selectDefaultBackend(properties::get, ignored -> null, properties::put);
        assertEquals("jline3", properties.get(VintelliMain.BACKEND_PROPERTY));
    }

    @Test
    void keepsConfiguredEnvironmentBackend() {
        Map<String, String> properties = new HashMap<>();
        VintelliMain.selectDefaultBackend(properties::get,
                name -> VintelliMain.BACKEND_ENV.equals(name) ? "jline3" : null, properties::put);
        assertNull(properties.get(VintelliMain.BACKEND_PROPERTY));
    }

    @Test
    void shellConfigEnablesMouseCaptureAndMotionTracking() {
        var config = VintelliMain.shellConfig(KeyboardMode.DEFAULT);
        assertTrue(config.mouseCapture());
        assertTrue(config.mouseMotion());
        assertNull(config.backend());
    }

    @Test
    void anIdleShellTicksAtMostOncePerSecond() {
        assertTrue(VintelliMain.shellConfig(KeyboardMode.DEFAULT).tickRate().compareTo(Duration.ofSeconds(1)) >= 0,
                "idle tick was " + VintelliMain.shellConfig(KeyboardMode.DEFAULT).tickRate());
    }

    @Test
    void theDefaultShellRunsTheKittyKeyboardProtocolAndLegacyOptsOut() {
        assertEquals(KeyboardProtocol.AUTO,
                VintelliMain.shellConfig(KeyboardMode.fromName("auto")).keyboardProtocol());
        assertEquals(KeyboardProtocol.LEGACY,
                VintelliMain.shellConfig(KeyboardMode.fromName("legacy")).keyboardProtocol());
    }

    @Test
    void resizeIsStillCheckedEveryFortyMilliseconds() {
        assertEquals(Duration.ofMillis(40), VintelliMain.shellConfig(KeyboardMode.DEFAULT).resizeGracePeriod());
    }

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
