package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import org.junit.jupiter.api.Test;

class IdeaStatusBadgeTest {

    private static final String URL = "http://127.0.0.1:9/stream";

    @Test
    void neverReadsTheConnectionWhenTheEndpointIsNotConfigured() {
        AtomicInteger reads = new AtomicInteger();
        IdeaStatusBadge badge = IdeaStatusBadge.of(Optional.empty(), countingConnection(reads, "CONNECTED"),
                () -> 0L, Runnable::run);

        assertFalse(badge.segment().isPresent());
        assertEquals(0, reads.get());
    }

    @Test
    void readsTheConnectionAtMostOncePerRefreshInterval() {
        AtomicInteger reads = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        IdeaStatusBadge badge = IdeaStatusBadge.of(Optional.of(URL), countingConnection(reads, "CONNECTED"),
                clock::get, Runnable::run);

        for (int render = 0; render < 100; render++) {
            badge.segment();
        }
        assertEquals(1, reads.get(), "a burst of renders reads the connection once");

        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        badge.segment();
        assertEquals(1, reads.get(), "a render inside the interval reuses the snapshot");

        clock.addAndGet(IdeaStatusBadge.REFRESH_INTERVAL.toNanos());
        badge.segment();
        assertEquals(2, reads.get(), "a render after the interval refreshes the snapshot");
    }

    @Test
    void anUnreachableIdeaNeverBlocksTheRenderThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch readStarted = new CountDownLatch(1);
        IdeaMcpConnection blocked = new IdeaMcpConnection() {
            @Override
            public Snapshot connectionStatus() {
                readStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new Snapshot("UNREACHABLE", "connect timed out");
            }

            @Override
            public Snapshot reconnectFromUserAction() {
                throw new UnsupportedOperationException();
            }
        };
        IdeaStatusBadge badge = IdeaStatusBadge.of(Optional.of(URL), blocked, () -> 0L,
                task -> Thread.ofVirtual().start(task));
        try {
            String first = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> badge.segment().orElseThrow().text());
            assertEquals("IDEA: disconnected", first,
                    "the render shows the last known state while the read is in flight");
            assertTrue(readStarted.await(5, TimeUnit.SECONDS), "the read happens off the render thread");

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String latest = first;
            while (!"IDEA: unreachable".equals(latest) && System.nanoTime() < deadline) {
                Thread.sleep(10);
                latest = badge.segment().orElseThrow().text();
            }
            assertEquals("IDEA: unreachable", latest);
        } finally {
            release.countDown();
        }
    }

    private static IdeaMcpConnection countingConnection(AtomicInteger reads, String state) {
        return new IdeaMcpConnection() {
            @Override
            public Snapshot connectionStatus() {
                reads.incrementAndGet();
                return new Snapshot(state, "reachable");
            }

            @Override
            public Snapshot reconnectFromUserAction() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
