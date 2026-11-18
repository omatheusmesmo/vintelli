package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.bindings.InputTrigger;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;

import java.util.Locale;

/**
 * A key trigger that requires Shift. The Kitty keyboard protocol reports Ctrl+Shift+letter as a chord distinct
 * from Ctrl+letter, but TamboUI has no Ctrl+Shift character trigger, so this wrapper adds the Shift check to
 * any trigger. The description names the chord with Shift, for example {@code Ctrl+Shift+N}, for the palette
 * and the help overlay; it expects a trigger whose description ends in its key name, as the key triggers do.
 */
record ShiftedKeyTrigger(KeyTrigger trigger) implements InputTrigger {

    @Override
    public boolean matches(Event event) {
        return event instanceof KeyEvent keyEvent && keyEvent.hasShift() && trigger.matches(event);
    }

    @Override
    public String describe() {
        String described = trigger.describe();
        int keyStart = described.lastIndexOf('+') + 1;
        String key = described.substring(keyStart);
        String shiftedKey = key.length() == 1 ? key.toUpperCase(Locale.ROOT) : key;
        return described.substring(0, keyStart) + "Shift+" + shiftedKey;
    }
}
