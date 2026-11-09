package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeymapHelpOverlayTest {

    private final Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, List.<ToolWindow>of());
    private final KeymapHelpOverlay overlay = new KeymapHelpOverlay(keymap);

    @Test
    void openAndCloseSwitchTheStateWithoutAnyKey() {
        assertFalse(overlay.isOpen());

        overlay.open();
        assertTrue(overlay.isOpen());

        overlay.close();
        assertFalse(overlay.isOpen());
    }

    @Test
    void escapeClosesTheOverlay() {
        overlay.open();

        overlay.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertFalse(overlay.isOpen());
    }

    @Test
    void theKeymapHelpChordClosesTheOverlayItOpened() {
        overlay.open();

        overlay.handleKey(KeyEvent.ofKey(KeyCode.F1));

        assertFalse(overlay.isOpen());
    }

    @Test
    void otherKeysAreSwallowedAndTheOverlayStaysOpen() {
        overlay.open();

        overlay.handleKey(KeyEvent.ofChar('a'));
        overlay.handleKey(KeyEvent.ofKey(KeyCode.DOWN));

        assertTrue(overlay.isOpen(), "the help overlay stays modal until Escape or its chord");
    }
}
