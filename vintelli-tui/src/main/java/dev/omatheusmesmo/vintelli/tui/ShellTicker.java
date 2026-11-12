package dev.omatheusmesmo.vintelli.tui;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;

/**
 * The shared animation tick of the shell. While an animation is running it
 * advances the animations and asks the render loop for a frame once per period.
 * With nothing animating it schedules nothing, so an idle shell stays idle. The
 * state is read and advanced under the frame lock, the same lock the render uses.
 */
final class ShellTicker {

    private final Duration period;
    private final Lock frameLock;
    private final BooleanSupplier animating;
    private final Runnable advance;
    private volatile Runnable redraw = () -> { };
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    ShellTicker(Duration period, Lock frameLock, BooleanSupplier animating, Runnable advance) {
        this.period = period;
        this.frameLock = frameLock;
        this.animating = animating;
        this.advance = advance;
    }

    void bindRedraw(Runnable redraw) {
        this.redraw = redraw;
    }

    /** Starts the tick when an animation runs and stops it when none does; the render calls it every frame. */
    void sync() {
        frameLock.lock();
        try {
            if (animating.getAsBoolean()) {
                start();
            } else {
                stop();
            }
        } finally {
            frameLock.unlock();
        }
    }

    /** One tick: advances the animations and requests a frame, or stops when nothing animates any more. */
    void beat() {
        frameLock.lock();
        try {
            if (!animating.getAsBoolean()) {
                stop();
                return;
            }
            advance.run();
        } finally {
            frameLock.unlock();
        }
        redraw.run();
    }

    private synchronized void start() {
        if (task != null) {
            return;
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "vintelli-shell-tick");
                thread.setDaemon(true);
                return thread;
            });
        }
        long millis = period.toMillis();
        task = scheduler.scheduleAtFixedRate(this::beat, millis, millis, TimeUnit.MILLISECONDS);
    }

    private synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
