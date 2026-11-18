package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShiftedKeyTriggerTest {

    private static final ShiftedKeyTrigger CTRL_SHIFT_N = new ShiftedKeyTrigger(KeyTrigger.ctrl('n'));

    @Test
    void describesTheChordWithShiftAndTheUpperCaseLetter() {
        assertEquals("Ctrl+Shift+N", CTRL_SHIFT_N.describe());
        assertEquals("Ctrl+Shift+Left",
                new ShiftedKeyTrigger(KeyTrigger.key(KeyCode.LEFT, true, false, false)).describe());
    }

    @Test
    void matchesTheKittyChordOnlyWhenShiftIsHeld() {
        KeyEvent kittyCtrlShiftN = KeyEvent.builder()
                .code(KeyCode.CHAR)
                .modifiers(KeyModifiers.of(true, false, true))
                .character('N')
                .keyCodePoint('n')
                .shiftedKeyCodePoint('N')
                .baseLayoutKeyCodePoint('n')
                .build();

        assertTrue(CTRL_SHIFT_N.matches(kittyCtrlShiftN));
        assertFalse(CTRL_SHIFT_N.matches(KeyEvent.ofChar('n', KeyModifiers.CTRL)),
                "plain Ctrl+N is not the shifted chord");
    }

    @Test
    void refusesTheChordWhenAnotherModifierIsHeld() {
        assertFalse(CTRL_SHIFT_N.matches(KeyEvent.ofChar('N', KeyModifiers.of(true, true, true))),
                "Ctrl+Alt+Shift+N is a different chord");
    }
}
