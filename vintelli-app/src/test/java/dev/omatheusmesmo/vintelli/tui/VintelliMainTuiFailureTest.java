package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.VintelliMain;
import io.quarkus.test.junit.main.QuarkusMainTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code --tui} entry point fails fast on a bad theme: the process prints
 * {@code Vintelli cannot start the TUI: <message>} and exits with code 2, and
 * it does so before any terminal is attached, so no TTY is required.
 */
@QuarkusMainTest
@TestProfile(VintelliMainTuiFailureTest.BogusThemeProfile.class)
class VintelliMainTuiFailureTest {

    public static class BogusThemeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "vintelli.tui.theme", "bogus",
                    "vintelli.config.dir", Path.of("target", "vintelli-main-tui-failure-config-empty").toAbsolutePath().toString());
        }
    }

    @Test
    void aBadThemePrintsTheOneLineMessageAndExitsWithCode2(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("--tui");
        assertEquals(2, result.exitCode());
        assertTrue(result.getErrorOutput().contains("Vintelli cannot start the TUI:"),
                "stderr was: " + result.getErrorOutput());
    }
}
