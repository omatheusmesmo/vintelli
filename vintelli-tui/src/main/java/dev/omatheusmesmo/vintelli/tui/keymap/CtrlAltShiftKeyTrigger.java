package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.bindings.InputTrigger;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;

/**
 * A letter chord with Ctrl, Alt and Shift held together, for example {@code Ctrl+Alt+Shift+N}. TamboUI's character
 * triggers check the Ctrl and Alt modifiers exactly and offer no combination of both, so this trigger checks them
 * itself. The Kitty keyboard protocol reports the chord as its own key; a terminal without the protocol cannot send
 * it distinctly from {@code Ctrl+Alt+N}.
 */
record CtrlAltShiftKeyTrigger(char letter) implements InputTrigger {

    @Override
    public boolean matches(Event event) {
        if (!(event instanceof KeyEvent keyEvent) || keyEvent.isRelease()) {
            return false;
        }
        return keyEvent.hasShift() && keyEvent.modifiers().ctrl() && keyEvent.modifiers().alt()
                && !keyEvent.modifiers().superKey() && !keyEvent.modifiers().hyper() && !keyEvent.modifiers().meta()
                && keyEvent.isCharIgnoreCase(letter);
    }

    @Override
    public String describe() {
        return "Ctrl+Alt+Shift+" + Character.toUpperCase(letter);
    }
}
