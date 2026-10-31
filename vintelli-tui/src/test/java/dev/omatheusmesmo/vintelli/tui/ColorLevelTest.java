package dev.omatheusmesmo.vintelli.tui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorLevelTest {

    private static final Map<String, String> PLAIN_TERMINAL = Map.of("TERM", "xterm-256color", "TERM_PROGRAM", "vscode");

    @Test
    void absentOrBlankConfigurationMeansAuto() {
        assertEquals(ColorLevel.AUTO, ColorLevel.fromConfig(null));
        assertEquals(ColorLevel.AUTO, ColorLevel.fromConfig("  "));
    }

    @Test
    void configurationValuesAreCaseAndWhitespaceInsensitive() {
        assertEquals(ColorLevel.TRUECOLOR, ColorLevel.fromConfig(" TRUECOLOR "));
        assertEquals(ColorLevel.INDEXED_256, ColorLevel.fromConfig("256"));
        assertEquals(ColorLevel.AUTO, ColorLevel.fromConfig("Auto"));
    }

    @Test
    void unknownConfigurationFailsFastNamingPropertyAndAllowedValues() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ColorLevel.fromConfig("16"));

        assertTrue(error.getMessage().contains("vintelli.tui.color-level"), error.getMessage());
        assertTrue(error.getMessage().contains("auto, truecolor, 256"), error.getMessage());
    }

    @Test
    void autoPicksTruecolorWhenColortermAdvertisesIt() {
        assertEquals(ColorLevel.TRUECOLOR, ColorLevel.AUTO.resolve(Map.of("COLORTERM", "truecolor")));
        assertEquals(ColorLevel.TRUECOLOR, ColorLevel.AUTO.resolve(Map.of("COLORTERM", "24bit")));
        assertEquals(ColorLevel.TRUECOLOR, ColorLevel.AUTO.resolve(Map.of("COLORTERM", "TRUECOLOR")));
        assertEquals(ColorLevel.TRUECOLOR, ColorLevel.AUTO.resolve(Map.of("COLORTERM", "24BIT")));
    }

    @Test
    void autoFallsBackTo256WithoutAUsableColorterm() {
        assertEquals(ColorLevel.INDEXED_256, ColorLevel.AUTO.resolve(PLAIN_TERMINAL));
        assertEquals(ColorLevel.INDEXED_256, ColorLevel.AUTO.resolve(Map.of("COLORTERM", "")));
        assertEquals(ColorLevel.INDEXED_256, ColorLevel.AUTO.resolve(Map.of("COLORTERM", "xterm-256color")));
    }

    @Test
    void explicitConfigurationOverridesTerminalDetection() {
        assertEquals(ColorLevel.TRUECOLOR, ColorLevel.TRUECOLOR.resolve(PLAIN_TERMINAL));
        assertEquals(ColorLevel.INDEXED_256, ColorLevel.INDEXED_256.resolve(Map.of("COLORTERM", "truecolor")));
    }

    @Test
    void sessionDepthFollowsTheResolvedLevelWhenBuiltFromEnvironment() {
        assertEquals(ColorLevel.TRUECOLOR, ColorLevel.of("auto", Map.of("COLORTERM", "truecolor")));
        assertEquals(ColorLevel.INDEXED_256, ColorLevel.of("auto", PLAIN_TERMINAL));
        assertEquals(ColorLevel.INDEXED_256, ColorLevel.of("256", Map.of("COLORTERM", "truecolor")));
        assertEquals(ColorLevel.TRUECOLOR, ColorLevel.of("truecolor", PLAIN_TERMINAL));
    }
}
