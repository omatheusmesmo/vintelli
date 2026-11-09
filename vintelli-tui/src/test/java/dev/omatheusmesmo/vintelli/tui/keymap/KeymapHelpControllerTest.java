package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeymapHelpControllerTest {

    private final KeymapHelpController help = new KeymapHelpController(List.of(
            new KeyAction("One", "One", ""),
            new KeyAction("Two", "Two", ""),
            new KeyAction("Three", "Three", ""),
            new KeyAction("Four", "Four", "")));

    @Test
    void openAndCloseSwitchTheStateWithoutAnyKey() {
        assertFalse(help.isOpen());

        help.open();
        assertTrue(help.isOpen());

        help.close();
        assertFalse(help.isOpen());
    }

    @Test
    void escapeAndTheHelpChordCloseTheOverlay() {
        help.open();
        help.press(OverlayKey.ESCAPE, false);
        assertFalse(help.isOpen());

        help.open();
        help.press(OverlayKey.OTHER, true);
        assertFalse(help.isOpen());
    }

    @Test
    void everyOtherKeyIsSwallowedAndTheOverlayStaysModal() {
        help.open();

        help.press(OverlayKey.TEXT, false);
        help.press(OverlayKey.DOWN, false);

        assertTrue(help.isOpen(), "the help overlay stays modal until Escape or its chord");
    }

    @Test
    void thePanelHoldsEveryActionPlusTheFooterWithAMinimumHeight() {
        assertEquals(9, help.panelHeight());
        assertEquals(6, new KeymapHelpController(List.of()).panelHeight());
    }

    @Test
    void actionsThatDoNotFitAreCountedInsteadOfCutOff() {
        assertEquals(2, help.shownActions(2));
        assertEquals(2, help.hiddenActions(2));
        assertEquals(0, help.hiddenActions(10));
    }
}
