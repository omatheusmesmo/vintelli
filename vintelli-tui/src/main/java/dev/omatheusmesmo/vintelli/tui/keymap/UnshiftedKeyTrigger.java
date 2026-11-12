package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.bindings.InputTrigger;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;

/**
 * A key trigger that refuses Shift. A TamboUI {@link KeyTrigger} checks Shift only when it asks for it, so a
 * bare {@code F2} trigger also answers {@code Shift+F2}; this wrapper keeps the bare chord and its Shift
 * variant on different actions.
 */
record UnshiftedKeyTrigger(KeyTrigger trigger) implements InputTrigger {

    @Override
    public boolean matches(Event event) {
        return event instanceof KeyEvent keyEvent && !keyEvent.hasShift() && trigger.matches(event);
    }

    @Override
    public String describe() {
        return trigger.describe();
    }
}
