package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

/**
 * The only place where the overlay elements turn TamboUI events and rectangles
 * into the plain values their controllers take. The order of the checks is the
 * order the overlays always used, so the same key means the same thing.
 */
public final class OverlayInput {

    private OverlayInput() {
    }

    public static OverlayKey key(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            return OverlayKey.ESCAPE;
        }
        if (key.isKey(KeyCode.ENTER)) {
            return OverlayKey.ENTER;
        }
        if (key.isKey(KeyCode.UP)) {
            return OverlayKey.UP;
        }
        if (key.isKey(KeyCode.DOWN)) {
            return OverlayKey.DOWN;
        }
        if (key.isKey(KeyCode.LEFT)) {
            return OverlayKey.LEFT;
        }
        if (key.isKey(KeyCode.RIGHT)) {
            return OverlayKey.RIGHT;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            return OverlayKey.PAGE_UP;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            return OverlayKey.PAGE_DOWN;
        }
        if (key.isKey(KeyCode.F10)) {
            return OverlayKey.F10;
        }
        if (key.code() == KeyCode.CHAR && key.codePoint() >= 32 && !key.hasCtrl() && !key.hasAlt()) {
            return OverlayKey.TEXT;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            return OverlayKey.BACKSPACE;
        }
        return OverlayKey.OTHER;
    }

    /** -1 for a wheel up, 1 for a wheel down, 0 for every other mouse event. */
    public static int scrollDirection(MouseEvent event) {
        if (event.kind() == MouseEventKind.SCROLL_UP) {
            return -1;
        }
        if (event.kind() == MouseEventKind.SCROLL_DOWN) {
            return 1;
        }
        return 0;
    }

    public static OverlayRegion region(Rect rect) {
        return new OverlayRegion(rect.x(), rect.y(), rect.width(), rect.height());
    }

    public static Rect rect(OverlayRegion region) {
        return new Rect(region.x(), region.y(), region.width(), region.height());
    }
}
