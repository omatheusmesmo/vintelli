package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.bindings.InputTrigger;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

/**
 * A key trigger for Ctrl+Alt+letter. TamboUI's character triggers compare the modifiers exactly, so a chord that
 * holds Ctrl and Alt together needs this trigger. Shift must not be held, so Ctrl+Alt+Shift+letter stays a chord of
 * its own. A Kitty-capable terminal reports the chord; a legacy terminal sends it as an escape followed by a control
 * byte, which the parser does not map to a key, so the chord reaches the shell only under the Kitty protocol.
 */
record CtrlAltKeyTrigger(char letter) implements InputTrigger {

    @Override
    public boolean matches(Event event) {
        if (!(event instanceof KeyEvent keyEvent) || keyEvent.isRelease()) {
            return false;
        }
        KeyModifiers modifiers = keyEvent.modifiers();
        boolean exactlyCtrlAndAlt = modifiers.ctrl() && modifiers.alt() && !modifiers.shift()
                && !modifiers.superKey() && !modifiers.hyper() && !modifiers.meta();
        return exactlyCtrlAndAlt && (keyEvent.isCharIgnoreCase(letter) || matchesLetter(keyEvent.keyCodePoint())
                || keyEvent.baseLayoutKeyCodePoint().isPresent()
                        && matchesLetter(keyEvent.baseLayoutKeyCodePoint().getAsInt()));
    }

    @Override
    public String describe() {
        return "Ctrl+Alt+" + Character.toUpperCase(letter);
    }

    private boolean matchesLetter(int codePoint) {
        return Character.toLowerCase(codePoint) == Character.toLowerCase(letter);
    }
}
