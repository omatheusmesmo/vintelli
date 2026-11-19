package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.text.CharWidth;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void aWideActionNameIsCutByCellsSoTheChordStaysAtTheRightEdge() {
        String label = "名前".repeat(20);
        String chord = "Ctrl+Shift+F";

        OverlayPanel.ChordRow row = OverlayPanel.ChordRow.fit(label, chord, "", 40);

        assertTrue(row.label().endsWith("…"), "a cut name says it is cut: " + row.label());
        assertEquals(40, CharWidth.of(" " + row.label() + row.filler() + row.chord()),
                "the chord ends at the right edge of the row");
    }

    @Test
    void anActionNameWithACharacterOutsideTheBmpIsNeverSplit() {
        String label = "a".repeat(10) + "🔧" + "b".repeat(10);

        OverlayPanel.ChordRow row = OverlayPanel.ChordRow.fit(label, "F1", "", 15);

        assertEquals("a".repeat(10) + "…", row.label());
        assertEquals("F1", row.chord());
    }
}
