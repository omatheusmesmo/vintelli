package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

class ShellTickerTest {

    private static final Duration PERIOD = Duration.ofMillis(10);

    @Test
    void aTickWithNothingAnimatingRequestsNoFrame() throws InterruptedException {
        AtomicInteger redraws = new AtomicInteger();
        AtomicInteger advances = new AtomicInteger();
        ShellTicker ticker = new ShellTicker(PERIOD, new ReentrantLock(), () -> false, advances::incrementAndGet);
        ticker.bindRedraw(redraws::incrementAndGet);

        ticker.sync();
        ticker.beat();
        Thread.sleep(10 * PERIOD.toMillis());

        assertEquals(0, redraws.get(), "an idle shell requests no frame");
        assertEquals(0, advances.get(), "nothing is advanced while nothing animates");
    }

    @Test
    void aRunningAnimationRequestsFramesUntilItStops() throws InterruptedException {
        AtomicInteger redraws = new AtomicInteger();
        AtomicBoolean animating = new AtomicBoolean(true);
        ShellTicker ticker = new ShellTicker(PERIOD, new ReentrantLock(), animating::get, () -> { });
        ticker.bindRedraw(redraws::incrementAndGet);

        ticker.sync();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (redraws.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(PERIOD.toMillis());
        }
        assertTrue(redraws.get() >= 3, "the tick requests a frame per period while an animation runs");

        animating.set(false);
        Thread.sleep(5 * PERIOD.toMillis());
        int stopped = redraws.get();
        Thread.sleep(10 * PERIOD.toMillis());

        assertEquals(stopped, redraws.get(), "the tick stops once no animation runs");
    }
}
