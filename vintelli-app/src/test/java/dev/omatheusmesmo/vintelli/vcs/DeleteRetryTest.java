package dev.omatheusmesmo.vintelli.vcs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteRetryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long FIRST_PAUSE = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long MAX_PAUSE = TimeUnit.MILLISECONDS.toNanos(250);

    @Test
    void succeedsAfterTheAttemptsThatFailedAreRetried() {
        FakeClock clock = new FakeClock();
        DeleteRetry retry = clock.retry();
        List<IOException> thrown = new ArrayList<>();
        int[] attempts = {0};

        Optional<IOException> result = retry.run(() -> {
            attempts[0]++;
            if (attempts[0] <= 2) {
                IOException failure = new DirectoryNotEmptyException("/tmp/repository/.git");
                thrown.add(failure);
                throw failure;
            }
        });

        assertFalse(result.isPresent(), "the third attempt succeeds, so no failure is reported");
        assertEquals(3, attempts[0]);
        assertEquals(List.of(FIRST_PAUSE, 2 * FIRST_PAUSE), clock.pauses());
    }

    @Test
    void givesUpAtTheDeadlineWithTheLastFailureAndBacksOffUpToTheCap() {
        FakeClock clock = new FakeClock();
        DeleteRetry retry = clock.retry();
        List<IOException> thrown = new ArrayList<>();

        Optional<IOException> result = retry.run(() -> {
            IOException failure = new DirectoryNotEmptyException("/tmp/repository/.git");
            thrown.add(failure);
            throw failure;
        });

        assertTrue(result.isPresent(), "a repository that never empties is reported, not retried forever");
        assertSame(thrown.get(thrown.size() - 1), result.get(), "the caller receives the last failure");
        List<Long> pauses = clock.pauses();
        assertEquals(List.of(10L, 20L, 40L, 80L, 160L, 250L), millisOf(pauses.subList(0, 6)),
                "the pauses double from 10 ms and stop growing at the 250 ms cap");
        assertTrue(pauses.stream().allMatch(pause -> pause <= MAX_PAUSE), "no pause exceeds the cap");
        assertEquals(TIMEOUT.toNanos(), pauses.stream().mapToLong(Long::longValue).sum(),
                "the pauses add up to exactly the timeout, the last one clipped to the deadline");
        assertEquals(thrown.size(), pauses.size() + 1, "one attempt runs before each pause and one after the last");
    }

    private static List<Long> millisOf(List<Long> nanos) {
        return nanos.stream().map(TimeUnit.NANOSECONDS::toMillis).toList();
    }

    /**
     * A clock that only moves when the retry pauses, so each run is deterministic
     * and no real time passes.
     */
    private static final class FakeClock {

        private final AtomicLong now = new AtomicLong(1_000_000_000L);
        private final List<Long> pauses = new ArrayList<>();

        DeleteRetry retry() {
            return new DeleteRetry(TIMEOUT, FIRST_PAUSE, MAX_PAUSE, now::get, nanos -> {
                pauses.add(nanos);
                now.addAndGet(nanos);
            });
        }

        List<Long> pauses() {
            return pauses;
        }
    }
}
