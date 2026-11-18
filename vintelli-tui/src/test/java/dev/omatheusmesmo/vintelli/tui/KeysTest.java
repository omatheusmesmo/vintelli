package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyEventKind;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeysTest {

    private static final Map<KeyCode, KeyCode> TWINS = new EnumMap<>(Map.ofEntries(
            Map.entry(KeyCode.KP_ENTER, KeyCode.ENTER),
            Map.entry(KeyCode.KP_UP, KeyCode.UP),
            Map.entry(KeyCode.KP_DOWN, KeyCode.DOWN),
            Map.entry(KeyCode.KP_LEFT, KeyCode.LEFT),
            Map.entry(KeyCode.KP_RIGHT, KeyCode.RIGHT),
            Map.entry(KeyCode.KP_HOME, KeyCode.HOME),
            Map.entry(KeyCode.KP_END, KeyCode.END),
            Map.entry(KeyCode.KP_PAGE_UP, KeyCode.PAGE_UP),
            Map.entry(KeyCode.KP_PAGE_DOWN, KeyCode.PAGE_DOWN),
            Map.entry(KeyCode.KP_INSERT, KeyCode.INSERT),
            Map.entry(KeyCode.KP_DELETE, KeyCode.DELETE)));

    @Test
    void eachKeypadNavigationKeyAnswersToItsMainKey() {
        TWINS.forEach((twin, main) -> {
            assertTrue(Keys.is(KeyEvent.ofKey(twin), main), twin + " answers to " + main);
            assertTrue(Keys.is(KeyEvent.ofKey(main), main), main + " still answers to itself");
        });
    }

    @Test
    void askingForAKeypadCodeMatchesOnlyThatCode() {
        TWINS.forEach((twin, main) -> {
            assertTrue(Keys.is(KeyEvent.ofKey(twin), twin), "the keypad code matches itself");
            assertFalse(Keys.is(KeyEvent.ofKey(main), twin), "the main key does not match " + twin);
        });
    }

    @Test
    void mainKeyKeepsTheModifiersAndTheKindOfTheKeypadKey() {
        KeyEvent repeatedCtrlHome = KeyEvent.ofKey(KeyCode.KP_HOME, KeyModifiers.CTRL, KeyEventKind.REPEAT);

        KeyEvent mainKey = Keys.mainKey(repeatedCtrlHome);

        assertEquals(KeyCode.HOME, mainKey.code());
        assertEquals(KeyModifiers.CTRL, mainKey.modifiers());
        assertEquals(KeyEventKind.REPEAT, mainKey.kind());
    }

    @Test
    void mainKeyKeepsTheBindingsTheKeypadKeyWasParsedWith() {
        Bindings vim = BindingSets.vim();
        KeyEvent keypadDown = KeyEvent.ofKey(KeyCode.KP_DOWN, KeyModifiers.NONE, KeyEventKind.PRESS, vim);

        assertSame(vim, Keys.mainKey(keypadDown).bindings(),
                "the text area reads its actions from the event bindings, so the keymap must survive the mapping");
    }

    @Test
    void mainKeyLeavesEveryOtherEventAsItWas() {
        KeyEvent plainEnter = KeyEvent.ofKey(KeyCode.ENTER);
        KeyEvent keypadDigit = KeyEvent.ofKey(KeyCode.KP_1);
        KeyEvent keypadAdd = KeyEvent.ofKey(KeyCode.KP_ADD);
        KeyEvent character = KeyEvent.ofChar('q');

        assertSame(plainEnter, Keys.mainKey(plainEnter));
        assertSame(keypadDigit, Keys.mainKey(keypadDigit), "keypad digits are text keys and keep their code");
        assertSame(keypadAdd, Keys.mainKey(keypadAdd), "keypad operators are text keys and keep their code");
        assertSame(character, Keys.mainKey(character));
    }

    @Test
    void overlayInputTypesTheKeypadTextKeysAndNeverAShortcut() {
        assertEquals(OverlayKey.TEXT, OverlayInput.key(KeyEvent.ofChar('a')));
        assertEquals(OverlayKey.TEXT, OverlayInput.key(new KeyEvent(KeyCode.KP_5, KeyModifiers.NONE, '5')),
                "a keypad digit types its character in an overlay prompt");
        assertEquals(OverlayKey.TEXT, OverlayInput.key(new KeyEvent(KeyCode.KP_ADD, KeyModifiers.NONE, '+')),
                "a keypad operator types its character in an overlay prompt");
        assertNotEquals(OverlayKey.TEXT, OverlayInput.key(KeyEvent.ofChar('s', KeyModifiers.CTRL)),
                "Ctrl+S is a shortcut, not text");
        assertNotEquals(OverlayKey.TEXT, OverlayInput.key(KeyEvent.ofKey(KeyCode.KP_ENTER)),
                "keypad Enter is not text");
    }
}
