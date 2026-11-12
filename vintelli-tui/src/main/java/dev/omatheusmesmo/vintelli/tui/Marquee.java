package dev.omatheusmesmo.vintelli.tui;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

import dev.tamboui.text.CharWidth;

/**
 * Scroll offset of a selected name that is wider than its cells. The name waits,
 * moves one cell per {@link #STEP}, waits at its end, moves back to the start and
 * waits again. {@link #tick()} advances the offset from the clock, and a render
 * only records the width through {@link #fit(int)}. The timeline restarts on every
 * selection change, a name that fits never moves, and a disabled marquee never moves.
 */
public final class Marquee {

    public static final Duration PAUSE = Duration.ofSeconds(1);
    public static final Duration STEP = Duration.ofMillis(150);
    private static final long PAUSE_NANOS = PAUSE.toNanos();
    private static final long STEP_NANOS = STEP.toNanos();

    private volatile LongSupplier clock;
    private volatile boolean enabled;
    private volatile long startedAt;
    private volatile int distance;
    private volatile int offset;
    private volatile Object selection;
    private volatile Runnable changed = () -> { };

    public Marquee(LongSupplier clock) {
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    public void useClock(LongSupplier clock) {
        this.clock = clock;
        restart();
    }

    public void enable(boolean on) {
        enabled = on;
        setDistance(0);
    }

    public boolean enabled() {
        return enabled;
    }

    /** Runs when the cells the name overflows by change, so the shell can start or stop its tick. */
    public void onChange(Runnable listener) {
        this.changed = listener;
    }

    /** Records how many cells the selected name overflows its view by; zero or less means it fits. */
    public void fit(int overflow) {
        setDistance(overflow);
    }

    public boolean moving() {
        return distance > 0;
    }

    public int offset() {
        return offset;
    }

    /** Starts the timeline again at the start of the name. */
    public void restart() {
        startedAt = clock.getAsLong();
        offset = 0;
    }

    /** Restarts the timeline when the selection named by {@code key} is not the one the last tick followed. */
    public void follow(Object key) {
        if (!Objects.equals(key, selection)) {
            selection = key;
            restart();
        }
    }

    public void tick() {
        offset = offsetAt(clock.getAsLong() - startedAt, distance);
    }

    /** The offset {@code elapsedNanos} after the start of the timeline, for a name that overflows by {@code distance}. */
    static int offsetAt(long elapsedNanos, int distance) {
        if (distance <= 0 || elapsedNanos < 0) {
            return 0;
        }
        long travel = distance * STEP_NANOS;
        long cycle = 2 * PAUSE_NANOS + 2 * travel;
        long phase = elapsedNanos % cycle;
        if (phase < PAUSE_NANOS) {
            return 0;
        }
        if (phase < PAUSE_NANOS + travel) {
            return (int) Math.min(distance, (phase - PAUSE_NANOS) / STEP_NANOS);
        }
        if (phase < 2 * PAUSE_NANOS + travel) {
            return distance;
        }
        long back = (phase - 2 * PAUSE_NANOS - travel) / STEP_NANOS;
        return (int) Math.max(0, distance - back);
    }

    /** The text from display column {@code fromColumn} that fits {@code columns} cells; a wide glyph cut at the edge leaves blank cells. */
    public static String window(String text, int fromColumn, int columns) {
        if (columns <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        int skipped = 0;
        while (index < text.length() && skipped < fromColumn) {
            int codePoint = text.codePointAt(index);
            skipped += CharWidth.of(codePoint);
            index += Character.charCount(codePoint);
        }
        int used = Math.max(0, skipped - fromColumn);
        out.append(" ".repeat(used));
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int width = CharWidth.of(codePoint);
            if (used + width > columns) {
                break;
            }
            out.appendCodePoint(codePoint);
            used += width;
            index += Character.charCount(codePoint);
        }
        return out.toString();
    }

    private void setDistance(int overflow) {
        int next = enabled ? Math.max(0, overflow) : 0;
        if (next == distance) {
            return;
        }
        distance = next;
        offset = Math.min(offset, next);
        changed.run();
    }
}
