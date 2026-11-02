package dev.omatheusmesmo.vintelli.tui;

import java.util.concurrent.ConcurrentLinkedQueue;

import jakarta.inject.Singleton;

/** Queues asynchronous provider results until the next shell render. */
@Singleton
public final class UiWorkQueue {

    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

    public void execute(Runnable update) {
        pending.add(update);
    }

    void drain() {
        Runnable update;
        while ((update = pending.poll()) != null) {
            update.run();
        }
    }
}
