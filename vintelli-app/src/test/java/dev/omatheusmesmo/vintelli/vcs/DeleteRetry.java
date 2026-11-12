package dev.omatheusmesmo.vintelli.vcs;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Runs a deletion again after each {@link IOException} until it succeeds or the
 * deadline passes, pausing with a doubling backoff capped at a maximum. The
 * clock and the pause are injected so the retry sequence can be driven without
 * real waiting or real file system races.
 */
final class DeleteRetry {

    @FunctionalInterface
    interface Attempt {

        void run() throws IOException;
    }

    private final Duration timeout;
    private final long firstPauseNanos;
    private final long maxPauseNanos;
    private final LongSupplier clockNanos;
    private final LongConsumer pauseNanos;

    DeleteRetry(Duration timeout, long firstPauseNanos, long maxPauseNanos,
            LongSupplier clockNanos, LongConsumer pauseNanos) {
        this.timeout = timeout;
        this.firstPauseNanos = firstPauseNanos;
        this.maxPauseNanos = maxPauseNanos;
        this.clockNanos = clockNanos;
        this.pauseNanos = pauseNanos;
    }

    static DeleteRetry withRealClock(Duration timeout, Duration firstPause, Duration maxPause) {
        return new DeleteRetry(timeout, firstPause.toNanos(), maxPause.toNanos(),
                System::nanoTime, nanos -> LockSupport.parkNanos(nanos));
    }

    /**
     * @return empty when an attempt succeeded, otherwise the failure of the last
     *         attempt made before the deadline
     */
    Optional<IOException> run(Attempt attempt) {
        long deadline = clockNanos.getAsLong() + timeout.toNanos();
        long backoffNanos = firstPauseNanos;
        IOException lastFailure;
        while (true) {
            try {
                attempt.run();
                return Optional.empty();
            } catch (IOException failure) {
                lastFailure = failure;
            }
            long remainingNanos = deadline - clockNanos.getAsLong();
            if (remainingNanos <= 0) {
                return Optional.of(lastFailure);
            }
            pauseNanos.accept(Math.min(backoffNanos, remainingNanos));
            backoffNanos = Math.min(backoffNanos * 2, maxPauseNanos);
        }
    }
}
