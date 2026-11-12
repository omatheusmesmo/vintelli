package dev.omatheusmesmo.vintelli.tui;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Singleton;

/** Queues asynchronous provider results until the next shell render. */
@Singleton
public final class UiWorkQueue {

    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wakeRequested = new AtomicBoolean();
    private volatile Runnable wake = () -> { };

    public void execute(Runnable update) {
        pending.add(update);
        if (wakeRequested.compareAndSet(false, true)) {
            wake.run();
        }
    }

    void bindWake(Runnable wake) {
        this.wake = wake;
    }

    void drain() {
        wakeRequested.set(false);
        Runnable update;
        while ((update = pending.poll()) != null) {
            update.run();
        }
    }
}
