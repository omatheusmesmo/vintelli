package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class UiWorkQueueTest {

    @Test
    void aBurstOfWorkWakesTheLoopOnceUntilTheQueueIsDrained() {
        UiWorkQueue queue = new UiWorkQueue();
        AtomicInteger wakes = new AtomicInteger();
        queue.bindWake(wakes::incrementAndGet);

        queue.execute(() -> { });
        queue.execute(() -> { });
        queue.execute(() -> { });
        assertEquals(1, wakes.get());

        queue.drain();
        queue.execute(() -> { });
        assertEquals(2, wakes.get());
    }
}
