package dev.omatheusmesmo.vintelli.tui.web;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.tui.event.UiRunnable;
import org.jboss.logging.Logger;

/**
 * One browser terminal: owns the per-connection TamboUI runner, backend and
 * worker thread, renders the shared live shell into its own screen buffer and
 * routes decoded key events into the shell. Nothing else writes to the shell:
 * the only mutation path is the key events the shell already accepts, the
 * same path the local TUI uses, so the surface stays read-only beyond input.
 *
 * <p>The worker loop polls events and draws at most one frame every
 * {@code MIN_FRAME_INTERVAL_MILLIS} (16 ms); TamboUI diffs every frame, so an
 * idle session transports nothing and a change (key press, highlight or diff
 * pushed from another surface) reaches the browser within the loop interval.
 * The loop also paints when a poll times out without an event, so the
 * 16 ms rule is a minimum spacing between any two paints, not a rule for
 * input-driven paints only.
 *
 * <p>The runner is built on the worker thread and its opening screen modes
 * (alternate screen, hidden cursor) stream through that thread too, so no
 * WebSocket callback ever blocks on sending screen output. When the shell
 * ends the session (the quit key through the shared keymap) or the backend
 * sees the connection gone, the loop closes the runner, which restores the
 * browser terminal (cursor shown, alternate screen left), and then the
 * backend, which repeats that restore as a fallback and drops every later
 * write. The owner hook then removes the session and closes the WebSocket.
 */
final class WebTerminalSession {

    private static final Logger LOG = Logger.getLogger(WebTerminalSession.class);
    static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    static final long MIN_FRAME_INTERVAL_MILLIS = 16;

    private final VintelliShell shell;
    private final WebTerminalBackend backend;
    private final Runnable sessionEnded;
    private final Thread worker;
    private final long idleTimeoutMillis;
    private final LongSupplier clock;
    private volatile TuiRunner runner;
    private volatile boolean stopRequested;
    private volatile long lastActivityMillis;

    WebTerminalSession(VintelliShell shell, String connectionId, WebTerminalOutput output, Runnable sessionEnded,
            Duration idleTimeout) {
        this(shell, connectionId, output, sessionEnded, idleTimeout, System::currentTimeMillis);
    }

    WebTerminalSession(VintelliShell shell, String connectionId, WebTerminalOutput output, Runnable sessionEnded,
            Duration idleTimeout, LongSupplier clock) {
        this.shell = shell;
        this.backend = new WebTerminalBackend(output);
        this.sessionEnded = sessionEnded;
        this.idleTimeoutMillis = idleTimeout == null ? 0 : idleTimeout.toMillis();
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.lastActivityMillis = this.clock.getAsLong();
        this.worker = new Thread(() -> loop(shell::paint), "vintelli-web-terminal-" + connectionId);
        worker.setDaemon(true);
    }

    /**
     * Starts the worker once; a repeated open callback keeps the first run.
     */
    void start() {
        if (worker.getState() == Thread.State.NEW) {
            worker.start();
        }
    }

    void stop() {
        stopRequested = true;
        touch();
        TuiRunner active = runner;
        if (active != null) {
            active.quit();
        }
    }

    void touch() {
        lastActivityMillis = clock.getAsLong();
    }

    void resize(int columns, int rows) {
        backend.updateSize(columns, rows);
        touch();
    }

    void acceptInput(byte[] bytes) {
        backend.acceptInput(bytes);
        touch();
    }

    void loop(Consumer<Frame> painter) {
        TuiRunner active;
        try {
            active = TuiRunner.create(TuiConfig.builder()
                    .backend(backend)
                    .noTick()
                    .rawMode(false)
                    .shutdownHook(false)
                    .build());
        } catch (Exception e) {
            LOG.warn("browser terminal runner could not start", e);
            sessionEnded.run();
            return;
        }
        runner = active;
        if (stopRequested) {
            active.quit();
        }
        try {
            drawInputLoop(active, painter);
        } finally {
            closeQuietly(active);
            sessionEnded.run();
        }
    }

    void drawInputLoop(TuiRunner active, Consumer<Frame> painter) {
        long lastDraw = clock.getAsLong() - MIN_FRAME_INTERVAL_MILLIS;
        while (active.isRunning()) {
            if (idleExpired()) {
                active.quit();
                break;
            }
            Event event = active.pollEvent(POLL_INTERVAL);
            if (event != null) {
                dispatch(event);
            }
            if (backend.closed() || !active.isRunning()) {
                break;
            }
            if (stopRequested) {
                active.quit();
                break;
            }
            long now = clock.getAsLong();
            if (throttled(now, lastDraw)) {
                continue;
            }
            try {
                active.draw(painter);
            } catch (RuntimeException e) {
                LOG.debug("browser terminal frame could not be drawn, ending the session", e);
                break;
            }
            lastDraw = clock.getAsLong();
            if (backend.closed()) {
                break;
            }
        }
    }

    boolean idleExpired() {
        if (idleTimeoutMillis <= 0) {
            return false;
        }
        return clock.getAsLong() - lastActivityMillis > idleTimeoutMillis;
    }

    private void closeQuietly(TuiRunner active) {
        try {
            active.close();
        } catch (RuntimeException e) {
            LOG.debug("browser terminal runner did not close cleanly", e);
        }
        try {
            backend.close();
        } catch (IOException e) {
            LOG.debug("browser terminal backend did not close cleanly", e);
        }
    }

    /**
     * Whether a draw that would happen now must be skipped because the
     * previous draw is less than {@link #MIN_FRAME_INTERVAL_MILLIS} ago.
     */
    static boolean throttled(long now, long lastDraw) {
        return now - lastDraw < MIN_FRAME_INTERVAL_MILLIS;
    }

    /**
     * Mirrors the dispatch rules of the TamboUI run loop: handler runnables
     * run here, ticks are absorbed (the loop draws on its own cadence) and
     * everything else goes to the shell, which routes the key events it
     * defines in its keymap.
     */
    private void dispatch(Event event) {
        if (event instanceof UiRunnable runnable) {
            runnable.run();
        } else if (!(event instanceof TickEvent)) {
            shell.handle(event);
        }
    }
}
