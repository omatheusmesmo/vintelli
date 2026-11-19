package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyEventKind;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CtrlAltKeyTriggerTest {

    private static final CtrlAltKeyTrigger CTRL_ALT_V = new CtrlAltKeyTrigger('v');
    private static final KeyModifiers CTRL_ALT = KeyModifiers.of(true, true, false);

    @Test
    void describesTheChordWithTheUpperCaseLetter() {
        assertEquals("Ctrl+Alt+V", CTRL_ALT_V.describe());
    }

    @Test
    void matchesTheChordWhenCtrlAndAltAreHeldTogether() {
        assertTrue(CTRL_ALT_V.matches(KeyEvent.ofChar('v', CTRL_ALT)));
    }

    @Test
    void matchesTheLatinLetterOfTheChordOnAForeignKeyboardLayout() {
        KeyEvent cyrillicKey = KeyEvent.builder()
                .code(KeyCode.CHAR)
                .modifiers(CTRL_ALT)
                .character('м')
                .keyCodePoint('м')
                .baseLayoutKeyCodePoint('v')
                .build();

        assertTrue(CTRL_ALT_V.matches(cyrillicKey));
    }

    @Test
    void refusesTheChordsThatHoldOnlyOneOfCtrlAndAltOrAnotherModifier() {
        assertFalse(CTRL_ALT_V.matches(KeyEvent.ofChar('v', KeyModifiers.CTRL)), "Ctrl+V is paste");
        assertFalse(CTRL_ALT_V.matches(KeyEvent.ofChar('v', KeyModifiers.ALT)), "Alt+V is the project view menu");
        assertFalse(CTRL_ALT_V.matches(KeyEvent.ofChar('V', KeyModifiers.of(true, true, true))),
                "Ctrl+Alt+Shift+V is a different chord");
    }

    @Test
    void refusesOtherLettersAndReleaseEvents() {
        assertFalse(new CtrlAltKeyTrigger('m').matches(KeyEvent.ofChar('v', CTRL_ALT)));
        assertFalse(CTRL_ALT_V.matches(KeyEvent.ofChar('v', CTRL_ALT, KeyEventKind.RELEASE)));
    }
}
