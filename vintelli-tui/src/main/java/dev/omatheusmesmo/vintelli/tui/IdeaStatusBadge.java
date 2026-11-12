package dev.omatheusmesmo.vintelli.tui;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import dev.omatheusmesmo.vintelli.tui.StatusBarLayout.Segment;

/**
 * The IDEA MCP segment of the status bar. It exists only when the IDEA endpoint is configured, and it draws the
 * last snapshot of the connection. The snapshot is read off the render thread, at most once per
 * {@link #REFRESH_INTERVAL} and only while the bar renders, so an unreachable IDEA never delays a frame and an
 * idle shell does not poll.
 */
final class IdeaStatusBadge {

    static final String ID = "idea";
    static final String WARNING_ID = "idea-warning";
    static final int PRIORITY = 55;
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(5);

    private static final String CONNECTED = "CONNECTED";
    private static final String DISCONNECTED = "DISCONNECTED";
    private static final IdeaMcpConnection.Snapshot NOT_CONNECTED_YET =
            new IdeaMcpConnection.Snapshot(DISCONNECTED, "not connected yet");

    private final IdeaMcpConnection connection;
    private final LongSupplier nanoTime;
    private final Executor background;
    private final AtomicLong nextRefreshAt;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile IdeaMcpConnection.Snapshot snapshot = NOT_CONNECTED_YET;

    private IdeaStatusBadge(IdeaMcpConnection connection, LongSupplier nanoTime, Executor background) {
        this.connection = connection;
        this.nanoTime = nanoTime;
        this.background = background;
        this.nextRefreshAt = new AtomicLong(nanoTime.getAsLong());
    }

    static IdeaStatusBadge of(Optional<String> ideaUrl, IdeaMcpConnection connection) {
        return of(ideaUrl, connection, System::nanoTime, task -> Thread.ofVirtual().start(task));
    }

    static IdeaStatusBadge of(Optional<String> ideaUrl, IdeaMcpConnection connection, LongSupplier nanoTime,
            Executor background) {
        boolean configured = connection != null && CapabilityStateLine.ideaConfigured(ideaUrl);
        return new IdeaStatusBadge(configured ? connection : null, nanoTime, background);
    }

    static IdeaStatusBadge absent() {
        return new IdeaStatusBadge(null, System::nanoTime, Runnable::run);
    }

    Optional<Segment> segment() {
        if (connection == null) {
            return Optional.empty();
        }
        refreshWhenDue();
        return Optional.of(segmentOf(snapshot));
    }

    static Segment segmentOf(IdeaMcpConnection.Snapshot status) {
        String state = status.state();
        boolean healthy = CONNECTED.equals(state) || DISCONNECTED.equals(state);
        String label = state.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Segment.of(healthy ? ID : WARNING_ID, PRIORITY, "IDEA: " + label);
    }

    private void refreshWhenDue() {
        long now = nanoTime.getAsLong();
        if (now - nextRefreshAt.get() < 0 || !refreshing.compareAndSet(false, true)) {
            return;
        }
        nextRefreshAt.set(now + REFRESH_INTERVAL.toNanos());
        background.execute(this::readSnapshot);
    }

    private void readSnapshot() {
        try {
            snapshot = connection.connectionStatus();
        } catch (RuntimeException failure) {
            snapshot = new IdeaMcpConnection.Snapshot("ERROR", "status read failed");
        } finally {
            refreshing.set(false);
        }
    }
}
