package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Key decisions for a selection in the built-in editor. The editor sees a key before the shell,
 * so copy wins over quit only while text is selected: without a selection Ctrl+C falls through
 * to the shell and quits as before.
 */
final class EditorSelectionKeys {

    private static final Set<KeyCode> SELECTING_KEYS = EnumSet.of(KeyCode.LEFT, KeyCode.RIGHT, KeyCode.UP,
            KeyCode.DOWN, KeyCode.HOME, KeyCode.END, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN);

    private EditorSelectionKeys() {
    }

    /**
     * Runs the copy, cut or paste chord of the keymap.
     *
     * @return true when the chord was consumed; Ctrl+C with no selection is not consumed
     */
    static boolean handleClipboardChord(KeyEvent event, Keymap keymap, EditorDocument document, BuiltinEditor editor) {
        if (keymap.matches(event, KeyActions.COPY)) {
            if (!document.hasSelection()) {
                return false;
            }
            editor.copySelection();
            return true;
        }
        if (keymap.matches(event, KeyActions.CUT)) {
            editor.cutSelection();
            return true;
        }
        if (keymap.matches(event, KeyActions.PASTE)) {
            editor.pasteClipboard();
            return true;
        }
        return false;
    }

    static boolean extendsSelection(KeyEvent event) {
        return event.hasShift() && !event.hasCtrl() && !event.hasAlt() && SELECTING_KEYS.contains(event.code());
    }

    static boolean selectsAll(KeyEvent event, Keymap keymap) {
        return keymap.matches(event, KeyActions.SELECT_ALL);
    }

    static boolean removesOnly(KeyEvent event) {
        return event.isKey(KeyCode.BACKSPACE) || event.isKey(KeyCode.DELETE);
    }

    static boolean replacesSelection(KeyEvent event) {
        return event.isKey(KeyCode.ENTER) || event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt();
    }
}
