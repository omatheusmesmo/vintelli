package dev.omatheusmesmo.vintelli.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class LogSettingsTest {

    private static final Path FILE = Path.of("/state/vintelli/vintelli.log");

    @Test
    void tuiTurnsTheConsoleOffAndLogsWarningsToTheFile() {
        assertEquals(Map.of(
                "quarkus.log.console.enabled", "false",
                "quarkus.log.file.enabled", "true",
                "quarkus.log.file.path", FILE.toString(),
                "quarkus.log.file.level", "WARN"),
                LogSettings.forMode(RunMode.TUI, false, Optional.of(FILE)));
    }

    @Test
    void tuiWithoutAWritableLogDirectoryOnlyTurnsTheConsoleOff() {
        assertEquals(Map.of("quarkus.log.console.enabled", "false"),
                LogSettings.forMode(RunMode.TUI, false, Optional.empty()));
    }

    @Test
    void cliAndReplSendOnlyErrorsToStderr() {
        Map<String, String> expected = Map.of(
                "quarkus.log.console.stderr", "true",
                "quarkus.log.console.level", "ERROR");
        assertEquals(expected, LogSettings.forMode(RunMode.CLI, false, Optional.empty()));
        assertEquals(expected, LogSettings.forMode(RunMode.REPL, false, Optional.empty()));
    }

    @Test
    void serverKeepsTheDefaultConsole() {
        assertTrue(LogSettings.forMode(RunMode.SERVER, false, Optional.empty()).isEmpty());
    }

    @Test
    void debugRaisesTheApplicationCategoryAndTheFileToDebug() {
        Map<String, String> settings = LogSettings.forMode(RunMode.CLI, true, Optional.of(FILE));
        assertEquals("DEBUG", settings.get("quarkus.log.category.\"dev.omatheusmesmo\".level"));
        assertEquals("DEBUG", settings.get("quarkus.log.file.level"));
        assertEquals("true", settings.get("quarkus.log.file.enabled"));
        assertEquals("ERROR", settings.get("quarkus.log.console.level"));
    }

    @Test
    void onlyTuiAndDebugWriteTheLogFile() {
        assertTrue(LogSettings.usesFile(RunMode.TUI, false));
        assertTrue(LogSettings.usesFile(RunMode.CLI, true));
        assertTrue(LogSettings.usesFile(RunMode.SERVER, true));
        assertFalse(LogSettings.usesFile(RunMode.CLI, false));
        assertFalse(LogSettings.usesFile(RunMode.REPL, false));
        assertFalse(LogSettings.usesFile(RunMode.SERVER, false));
    }

    @Test
    void settingsTheUserAlreadyConfiguredAreKept() {
        Map<String, String> settings = Map.of(
                "quarkus.log.console.level", "ERROR",
                "quarkus.log.file.enabled", "true",
                "quarkus.log.console.stderr", "true");
        Properties properties = new Properties();
        properties.setProperty("quarkus.log.console.level", "DEBUG");
        Map<String, String> env = Map.of("QUARKUS_LOG_FILE_ENABLED", "false");

        assertEquals(Map.of("quarkus.log.console.stderr", "true"),
                LogSettings.notYetConfigured(settings, properties, env));
    }

    @Test
    void environmentNamesFollowTheMicroProfileMapping() {
        assertEquals("QUARKUS_LOG_CATEGORY__DEV_OMATHEUSMESMO__LEVEL",
                LogSettings.environmentName("quarkus.log.category.\"dev.omatheusmesmo\".level"));
    }
}
