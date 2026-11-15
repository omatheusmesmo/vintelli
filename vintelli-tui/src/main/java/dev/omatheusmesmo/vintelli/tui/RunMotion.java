package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunCompletion;
import dev.tamboui.widgets.spinner.SpinnerStyle;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongSupplier;

/** Clock-driven run timing, spinner phase and bounded output-rate history. */
final class RunMotion {

    private static final long SAMPLE_MILLIS = 1_000;
    private static final int SAMPLE_COUNT = 12;
    private static final String[] ASCII_LINE = { "|", "/", "-", "\\" };

    private final LongSupplier clock;
    private final int[] outputSamples = new int[SAMPLE_COUNT];
    private RunMotionState state = RunMotionState.IDLE;
    private long runStartedAt;
    private long stateStartedAt;
    private long lastSampleAt;
    private int pendingOutputLines;
    private int nextSample;
    private boolean reduced;

    RunMotion(LongSupplier clock, boolean reduced) {
        this.clock = clock;
        this.reduced = reduced;
    }

    synchronized void start() {
        long now = nowMillis();
        state = RunMotionState.STARTING;
        runStartedAt = now;
        stateStartedAt = now;
        lastSampleAt = now;
        pendingOutputLines = 0;
        Arrays.fill(outputSamples, 0);
        nextSample = 0;
    }

    synchronized void running() {
        transition(RunMotionState.RUNNING);
    }

    synchronized void stopping() {
        transition(RunMotionState.STOPPING);
    }

    synchronized void failed() {
        transition(RunMotionState.FAILED);
    }

    synchronized void completed(RunCompletion completion) {
        transition(RunMotionState.completed(completion));
    }

    private void transition(RunMotionState next) {
        state = next;
        stateStartedAt = nowMillis();
    }

    synchronized RunMotionState state() {
        return state;
    }

    synchronized boolean active() {
        return state.active();
    }

    synchronized boolean animating() {
        if (reduced) {
            return state.active();
        }
        if (state.active() && !reduced) {
            return true;
        }
        long duration = effectDurationMillis(state);
        return duration > 0 && nowMillis() - stateStartedAt < duration;
    }

    synchronized Duration tickRate() {
        if (reduced) {
            return Duration.ofSeconds(1);
        }
        return Duration.ofMillis(state == RunMotionState.STARTING ? 80 : 120);
    }

    synchronized long elapsedMillis() {
        if (state == RunMotionState.IDLE) {
            return 0;
        }
        long end = state.active() ? nowMillis() : stateStartedAt;
        return Math.max(0, end - runStartedAt);
    }

    synchronized String glyph(boolean ascii) {
        if (!state.active() || reduced) {
            return ascii ? asciiGlyph(state) : state.glyph();
        }
        long interval = state == RunMotionState.STARTING ? 80 : 120;
        long frame = Math.max(0, nowMillis() - stateStartedAt) / interval;
        if (state == RunMotionState.STOPPING) {
            return ascii ? ASCII_LINE[(int) (frame % ASCII_LINE.length)] : SpinnerStyle.LINE.frame((int) frame);
        }
        return ascii ? ASCII_LINE[(int) (frame % ASCII_LINE.length)] : SpinnerStyle.DOTS.frame((int) frame);
    }

    synchronized void outputLine() {
        if (state.active() && state != RunMotionState.STOPPING) {
            pendingOutputLines++;
        }
    }

    /** Called by the active animation tick; output bursts never schedule rendering. */
    synchronized void tick() {
        if (!state.active() || state == RunMotionState.STOPPING) {
            return;
        }
        long now = nowMillis();
        long elapsed = Math.max(0, now - lastSampleAt);
        if (elapsed < SAMPLE_MILLIS) {
            return;
        }
        long intervals = elapsed / SAMPLE_MILLIS;
        outputSamples[nextSample] = pendingOutputLines;
        nextSample = (nextSample + 1) % SAMPLE_COUNT;
        pendingOutputLines = 0;
        for (long skipped = 1; skipped < intervals; skipped++) {
            outputSamples[nextSample] = 0;
            nextSample = (nextSample + 1) % SAMPLE_COUNT;
        }
        lastSampleAt += intervals * SAMPLE_MILLIS;
    }

    synchronized int[] outputSamples() {
        int[] ordered = new int[SAMPLE_COUNT];
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            ordered[i] = outputSamples[(nextSample + i) % SAMPLE_COUNT];
        }
        return ordered;
    }

    synchronized String outputPulse(boolean ascii) {
        if (reduced || state == RunMotionState.IDLE) {
            return "";
        }
        int maximum = Arrays.stream(outputSamples).max().orElse(0);
        String levels = ascii ? "_.-^" : "▁▂▃▄";
        int levelCount = levels.codePointCount(0, levels.length());
        StringBuilder pulse = new StringBuilder(SAMPLE_COUNT);
        for (int sample : outputSamples()) {
            int level = maximum == 0 ? 0 : Math.min(levelCount - 1, sample * (levelCount - 1) / maximum);
            pulse.appendCodePoint(levels.codePointAt(level));
        }
        return pulse.toString();
    }

    synchronized double effectProgress() {
        long duration = effectDurationMillis(state);
        if (duration == 0) {
            return 1;
        }
        return Math.min(1, Math.max(0, (nowMillis() - stateStartedAt) / (double) duration));
    }

    synchronized void reduced(boolean reduced) {
        this.reduced = reduced;
    }

    synchronized boolean reduced() {
        return reduced;
    }

    private static long effectDurationMillis(RunMotionState state) {
        return switch (state) {
            case SUCCEEDED -> 600;
            case FAILED -> 400;
            default -> 0;
        };
    }

    private static String asciiGlyph(RunMotionState state) {
        return switch (state) {
            case IDLE -> ">";
            case STARTING, RUNNING, STOPPING -> "|";
            case SUCCEEDED -> "v";
            case FAILED -> "x";
            case STOPPED -> "#";
        };
    }

    private long nowMillis() {
        return clock.getAsLong() / 1_000_000;
    }
}
