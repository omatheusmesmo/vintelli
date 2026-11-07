package dev.omatheusmesmo.vintelli.tui;

/**
 * Recognizes a double click on a list row: a press on the same row index as the
 * previous press, within {@link #WINDOW_NANOS}. The list overlays and the project
 * tree share this one definition of the timing.
 */
public final class DoubleClickTracker {

    /** The longest gap between two presses on one row that still counts as a double click. */
    public static final long WINDOW_NANOS = 500_000_000L;

    private int lastIndex = -1;
    private long lastNanos;

    /**
     * Records a press on {@code index} and reports whether it completes a double click.
     * A completed double click starts over, so a third press is a new first click.
     *
     * @param index the row index the press landed on
     * @param nanos the monotonic time of the press, as from {@link System#nanoTime()}
     * @return true when the previous press hit the same row within the window
     */
    public boolean register(int index, long nanos) {
        boolean doubled = index == lastIndex && nanos - lastNanos <= WINDOW_NANOS;
        if (doubled) {
            reset();
        } else {
            lastIndex = index;
            lastNanos = nanos;
        }
        return doubled;
    }

    /** Forgets the previous press, so the next press starts a new click on any row. */
    public void reset() {
        lastIndex = -1;
    }
}
