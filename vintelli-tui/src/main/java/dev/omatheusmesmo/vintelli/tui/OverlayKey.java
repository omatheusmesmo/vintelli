package dev.omatheusmesmo.vintelli.tui;

/**
 * The keys the overlays react to, translated from TamboUI key events by
 * {@link OverlayInput}. {@link #TEXT} is a printable character without Ctrl or
 * Alt, which filters and search fields take as input; {@link #OTHER} is every
 * key an overlay ignores.
 */
public enum OverlayKey {
    ESCAPE,
    ENTER,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    PAGE_UP,
    PAGE_DOWN,
    F10,
    BACKSPACE,
    TEXT,
    OTHER
}
