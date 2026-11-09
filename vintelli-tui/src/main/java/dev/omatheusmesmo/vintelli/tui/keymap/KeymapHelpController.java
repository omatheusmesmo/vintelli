package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;

import java.util.List;

/**
 * State and rules of the keymap help overlay: whether it is open, which key
 * closes it and how many actions fit the panel. It holds no TamboUI type.
 */
final class KeymapHelpController {

    private final List<KeyAction> actions;
    private boolean open;

    KeymapHelpController(List<KeyAction> actions) {
        this.actions = List.copyOf(actions);
    }

    void open() {
        open = true;
    }

    void close() {
        open = false;
    }

    boolean isOpen() {
        return open;
    }

    /** Escape and the keymap help chord close the overlay; every other key is swallowed. */
    void press(OverlayKey key, boolean keymapHelpChord) {
        if (key == OverlayKey.ESCAPE || keymapHelpChord) {
            close();
        }
    }

    List<KeyAction> actions() {
        return actions;
    }

    int panelHeight() {
        return Math.max(6, actions.size() + 5);
    }

    int shownActions(int visibleRows) {
        return Math.min(actions.size(), visibleRows);
    }

    int hiddenActions(int visibleRows) {
        return actions.size() - shownActions(visibleRows);
    }
}
