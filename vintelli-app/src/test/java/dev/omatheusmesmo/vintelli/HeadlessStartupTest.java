package dev.omatheusmesmo.vintelli;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A theme the shell cannot resolve (an unknown name of {@code vintelli.tui.theme},
 * or the user theme file it would lead to) must never keep the headless entry
 * point from booting: nothing builds the keymap, the tool windows or
 * {@link dev.omatheusmesmo.vintelli.tui.ShellThemes} before the {@code --tui}
 * branch asks for them, where the failure becomes the one line
 * {@code Vintelli cannot start the TUI: <message>} message with exit code 2.
 */
@QuarkusTest
@TestProfile(HeadlessStartupTest.BogusThemeProfile.class)
class HeadlessStartupTest {

    public static class BogusThemeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "vintelli.tui.theme", "bogus",
                    "vintelli.config.dir", Path.of("target", "headless-startup-test-config-empty").toAbsolutePath().toString());
        }
    }

    @Inject
    VintelliMain main;

    @Test
    void aBadThemeNeverBlocksTheHeadlessEntryPoint() {
        assertNotNull(main);
    }
}
