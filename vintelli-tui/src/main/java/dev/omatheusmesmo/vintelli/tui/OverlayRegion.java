package dev.omatheusmesmo.vintelli.tui;

/**
 * A rectangle in terminal cells, as an overlay controller holds it for hit
 * testing. It is the plain counterpart of a TamboUI rectangle, so the rules
 * that decide what a pointer hit means stay free of TamboUI types.
 */
public record OverlayRegion(int x, int y, int width, int height) {

    public static final OverlayRegion EMPTY = new OverlayRegion(0, 0, 0, 0);

    public boolean contains(int pointX, int pointY) {
        return width > 0 && height > 0 && pointX >= x && pointX < x + width
                && pointY >= y && pointY < y + height;
    }

    /** The region left after a one cell border on every side, as a bordered block draws it. */
    public OverlayRegion inset(int margin) {
        return new OverlayRegion(x + margin, y + margin,
                Math.max(0, width - 2 * margin), Math.max(0, height - 2 * margin));
    }
}
