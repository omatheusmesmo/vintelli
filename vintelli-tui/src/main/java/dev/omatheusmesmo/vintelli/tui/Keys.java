package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Key tests that treat the keypad navigation keys as their main keys. The Kitty keyboard protocol
 * reports keypad Enter, the arrows, Home, End, the page keys, Insert and Delete as distinct key
 * codes, while {@link KeyEvent#isKey(KeyCode)} compares the code strictly and TamboUI offers no hook
 * before event routing, so every call site asks through this class.
 */
public final class Keys {

    private Keys() {
    }

    public static boolean is(KeyEvent event, KeyCode code) {
        return event.isKey(code) || mainCode(event.code()) == code;
    }

    public static KeyEvent mainKey(KeyEvent event) {
        KeyCode main = mainCode(event.code());
        if (main == event.code()) {
            return event;
        }
        return KeyEvent.ofKey(main, event.modifiers(), event.kind());
    }

    private static KeyCode mainCode(KeyCode code) {
        return switch (code) {
            case KP_ENTER -> KeyCode.ENTER;
            case KP_UP -> KeyCode.UP;
            case KP_DOWN -> KeyCode.DOWN;
            case KP_LEFT -> KeyCode.LEFT;
            case KP_RIGHT -> KeyCode.RIGHT;
            case KP_HOME -> KeyCode.HOME;
            case KP_END -> KeyCode.END;
            case KP_PAGE_UP -> KeyCode.PAGE_UP;
            case KP_PAGE_DOWN -> KeyCode.PAGE_DOWN;
            case KP_INSERT -> KeyCode.INSERT;
            case KP_DELETE -> KeyCode.DELETE;
            default -> code;
        };
    }
}
