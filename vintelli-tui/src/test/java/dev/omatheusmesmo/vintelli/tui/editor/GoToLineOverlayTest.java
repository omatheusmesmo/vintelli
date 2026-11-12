package dev.omatheusmesmo.vintelli.tui.editor;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoToLineOverlayTest {

    private static KeyEvent text(char value) {
        return KeyEvent.ofChar(value);
    }

    private static KeyEvent key(KeyCode code) {
        return KeyEvent.ofKey(code);
    }

    private static GoToLineOverlay typed(String value) {
        GoToLineOverlay overlay = new GoToLineOverlay();
        overlay.open();
        for (char character : value.toCharArray()) {
            overlay.handleKey(text(character));
        }
        return overlay;
    }

    @Test
    void enterOnAValidTargetConfirmsOnceAndCloses() {
        GoToLineOverlay overlay = typed("12:3");

        Optional<GoToLine> target = overlay.handleKey(key(KeyCode.ENTER));

        assertEquals(Optional.of(new GoToLine(12, 3)), target);
        assertFalse(overlay.isOpen());
    }

    @Test
    void enterOnInvalidInputKeepsTheInputOpenAndSaysWhy() {
        GoToLineOverlay overlay = typed("0");

        Optional<GoToLine> target = overlay.handleKey(key(KeyCode.ENTER));

        assertTrue(target.isEmpty());
        assertTrue(overlay.isOpen(), "the input stays open so the number can be corrected");
        assertEquals(GoToLineOverlay.INVALID, overlay.status());
    }

    @Test
    void backspaceCorrectsTheTypedNumber() {
        GoToLineOverlay overlay = typed("0x");
        overlay.handleKey(key(KeyCode.BACKSPACE));
        overlay.handleKey(text('9'));

        assertEquals(Optional.of(new GoToLine(9, 1)), overlay.handleKey(key(KeyCode.ENTER)));
    }

    @Test
    void escapeCancelsWithoutATargetAndClosesTheInput() {
        GoToLineOverlay overlay = typed("45");

        assertTrue(overlay.handleKey(key(KeyCode.ESCAPE)).isEmpty());
        assertFalse(overlay.isOpen());
    }

    @Test
    void reopeningStartsFromAnEmptyInputWithTheHint() {
        GoToLineOverlay overlay = typed("0");
        overlay.handleKey(key(KeyCode.ENTER));
        overlay.handleKey(key(KeyCode.ESCAPE));

        overlay.open();

        assertEquals(GoToLineOverlay.HINT, overlay.status());
        assertEquals(Optional.empty(), overlay.handleKey(key(KeyCode.ENTER)));
        assertTrue(overlay.isOpen());
    }

    @Test
    void keysAreIgnoredWhileClosed() {
        GoToLineOverlay overlay = new GoToLineOverlay();

        assertTrue(overlay.handleKey(text('4')).isEmpty());
        overlay.open();

        assertTrue(overlay.handleKey(key(KeyCode.ENTER)).isEmpty(), "the digit typed while closed was not kept");
        assertTrue(overlay.isOpen());
    }
}
