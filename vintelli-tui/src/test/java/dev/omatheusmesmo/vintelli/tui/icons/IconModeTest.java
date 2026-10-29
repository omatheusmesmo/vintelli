package dev.omatheusmesmo.vintelli.tui.icons;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconModeTest {

    private static final Map<String, String> PLAIN_TERMINAL = Map.of("TERM", "xterm-256color", "TERM_PROGRAM", "vscode");

    @Test
    void absentOrBlankConfigurationMeansAuto() {
        assertEquals(IconMode.AUTO, IconMode.fromConfig(null));
        assertEquals(IconMode.AUTO, IconMode.fromConfig("  "));
    }

    @Test
    void configurationValuesAreCaseAndWhitespaceInsensitive() {
        assertEquals(IconMode.NERD, IconMode.fromConfig(" NERD "));
        assertEquals(IconMode.ASCII, IconMode.fromConfig("ascii"));
    }

    @Test
    void unknownConfigurationFailsFastNamingPropertyAndAllowedValues() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IconMode.fromConfig("emoji"));

        assertTrue(error.getMessage().contains("vintelli.icons"));
        assertTrue(error.getMessage().contains("auto, nerd, ascii"));
    }

    @Test
    void autoPicksNerdOnTerminalsThatBundleTheSymbolFont() {
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("KITTY_WINDOW_ID", "1")));
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("KITTY_PID", "4242")));
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("GHOSTTY_RESOURCES_DIR", "/usr/share/ghostty")));
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("WEZTERM_EXECUTABLE", "/usr/bin/wezterm")));
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("TERM_PROGRAM", "WezTerm")));
    }

    @Test
    void autoFallsBackToAsciiOnAnyOtherTerminal() {
        assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(PLAIN_TERMINAL));
        assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(Map.of("KITTY_PID", " ")));
    }

    @Test
    void explicitConfigurationOverridesTerminalDetection() {
        assertEquals(IconMode.NERD, IconMode.NERD.resolve(PLAIN_TERMINAL));
        assertEquals(IconMode.ASCII, IconMode.ASCII.resolve(Map.of("KITTY_WINDOW_ID", "1")));
    }

    @Test
    void sessionFaceFollowsTheResolvedModeWhenBuiltFromEnvironment() {
        assertEquals(IconMode.NERD, FileIcons.of("auto", Map.of("KITTY_WINDOW_ID", "1"), null).mode());
        assertEquals(IconMode.ASCII, FileIcons.of("auto", PLAIN_TERMINAL, null).mode());
        assertEquals(IconMode.NERD, FileIcons.of("nerd", PLAIN_TERMINAL, null).mode());
    }
}
