package dev.omatheusmesmo.vintelli.tui.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import dev.omatheusmesmo.vintelli.tui.ProblemsToolWindow;
import dev.omatheusmesmo.vintelli.tui.RunToolWindow;
import dev.omatheusmesmo.vintelli.tui.ShellState;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.TerminalToolWindow;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.omatheusmesmo.vintelli.tui.ToolWindowRegistry;
import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import org.junit.jupiter.api.Test;

class WebTerminalSessionTest {

    private static final long START_MILLIS = 1000L;

    private static ShellThemes defaultThemes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private static ToolWindowRegistry defaultRegistry() {
        ShellThemes themes = defaultThemes();
        return new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes),
                new TerminalToolWindow(() -> themes),
                new ProblemsToolWindow(() -> themes)));
    }

    private static VintelliShell newShell() {
        return new VintelliShell(defaultRegistry().toolWindows(), new ShellState(), defaultThemes());
    }

    @Test
    void stopBeforeRunnerAssignmentDoesNotLoseTheQuit() throws Exception {
        VintelliShell shell = newShell();
        List<String> output = new ArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        WebTerminalSession session = new WebTerminalSession(shell, "conn", output::add, ended::countDown,
                Duration.ZERO);
        session.stop();
        session.start();
        assertTrue(ended.await(5, TimeUnit.SECONDS), "stop before runner assignment must terminate the session");
    }

    @Test
    void burstWithinSixteenMillisDrawsOnlyForTheFirstInput() throws Exception {
        assertEquals(1, drawsForBurstOfTwoInputs(0L));
    }

    @Test
    void secondInputOnceTheSixteenMillisWindowHasPassedDrawsAgain() throws Exception {
        assertEquals(2, drawsForBurstOfTwoInputs(WebTerminalSession.MIN_FRAME_INTERVAL_MILLIS));
    }

    /**
     * Queues two tool window switches before the loop starts, freezes the clock
     * and, the moment the shell has applied the second switch, moves the clock
     * by {@code advanceMillis} and stops the session from the worker thread.
     * Returns how many frames the loop painted, so the count depends only on
     * the throttle decision for the second input.
     */
    private static int drawsForBurstOfTwoInputs(long advanceMillis) throws Exception {
        VintelliShell shell = newShell();
        List<ToolWindow> windows = defaultRegistry().toolWindows();
        String burst = "\u001b" + windows.get(1).shortcut() + "\u001b" + windows.get(2).shortcut();
        SecondInputClock clock = new SecondInputClock(START_MILLIS, advanceMillis,
                () -> shell.toolIndex() == 2);
        List<String> output = new ArrayList<>();
        WebTerminalSession session = new WebTerminalSession(shell, "throttle", output::add, () -> { },
                Duration.ZERO, clock);
        clock.stopWith(session);
        AtomicInteger drawn = new AtomicInteger();

        session.acceptInput(burst.getBytes(StandardCharsets.UTF_8));
        clock.arm();
        Thread worker = new Thread(() -> session.loop(frame -> drawn.incrementAndGet()));
        worker.start();
        worker.join(10_000);

        assertFalse(worker.isAlive(), "the loop must end once the clock stopped the session");
        assertEquals(2, shell.toolIndex(), "both queued inputs must have reached the shell");
        return drawn.get();
    }

    @Test
    void injectedClockDrivesIdleExpiry() {
        AtomicLong now = new AtomicLong(START_MILLIS);
        List<String> output = new ArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        WebTerminalSession session = new WebTerminalSession(newShell(), "conn2", output::add, ended::countDown,
                Duration.ofMillis(50), now::get);
        assertFalse(session.idleExpired(), "session must not be idle immediately");
        now.set(START_MILLIS + 100L);
        assertTrue(session.idleExpired(), "session must be idle once the injected clock passes the timeout");
        session.stop();
    }

    /**
     * Frozen clock driven by the worker loop itself. Once armed, the first
     * reading after the observed condition holds adds {@code advanceMillis};
     * the reading after that stops the session. The stop is requested from the
     * worker thread only after the draw decision for the second input, so no
     * sleep or cross-thread timing is involved.
     */
    private static final class SecondInputClock implements LongSupplier {

        private final AtomicLong now;
        private final long advanceMillis;
        private final BooleanSupplier condition;
        private final AtomicInteger readsSinceCondition = new AtomicInteger();
        private volatile boolean armed;
        private volatile WebTerminalSession session;

        SecondInputClock(long start, long advanceMillis, BooleanSupplier condition) {
            this.now = new AtomicLong(start);
            this.advanceMillis = advanceMillis;
            this.condition = condition;
        }

        void stopWith(WebTerminalSession target) {
            this.session = target;
        }

        void arm() {
            this.armed = true;
        }

        @Override
        public long getAsLong() {
            if (armed && condition.getAsBoolean()) {
                int reads = readsSinceCondition.incrementAndGet();
                if (reads == 1) {
                    return now.addAndGet(advanceMillis);
                }
                if (reads == 2) {
                    session.stop();
                }
            }
            return now.get();
        }
    }
}
