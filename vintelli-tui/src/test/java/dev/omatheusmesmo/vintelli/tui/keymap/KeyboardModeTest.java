package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.terminal.KeyboardProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyboardModeTest {

    @Test
    void theDefaultModeIsAutoAndLegacyIsTheOptOut() {
        assertEquals(KeyboardMode.AUTO, KeyboardMode.DEFAULT);
        assertEquals("auto", KeyboardMode.DEFAULT.displayName());
        assertEquals(KeyboardProtocol.AUTO, KeyboardMode.AUTO.protocol());
        assertEquals(KeyboardProtocol.LEGACY, KeyboardMode.LEGACY.protocol());
    }

    @Test
    void theModeNamesResolveCaseInsensitively() {
        assertEquals(KeyboardMode.AUTO, KeyboardMode.fromName("auto"));
        assertEquals(KeyboardMode.AUTO, KeyboardMode.fromName("AUTO"));
        assertEquals(KeyboardMode.LEGACY, KeyboardMode.fromName("Legacy"));
    }

    @Test
    void anUnknownModeFailsFastNamingTheValidOnes() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> KeyboardMode.fromName("full"));

        assertTrue(failure.getMessage().contains("'full'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("valid protocols: auto, legacy"), failure.getMessage());
    }
}
