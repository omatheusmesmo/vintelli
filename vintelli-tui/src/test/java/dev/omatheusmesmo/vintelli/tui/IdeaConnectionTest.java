package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;

class IdeaConnectionTest {

    @Test
    void ignoresASecondRequestWhileTheFirstConnectionRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        RecordingHost host = new RecordingHost(List.of());
        IdeaConnection connection = new IdeaConnection(new BlockingConnection(() -> {
            requests.incrementAndGet();
            started.countDown();
            finish.await(5, TimeUnit.SECONDS);
            return new IdeaMcpConnection.Snapshot("CONNECTED", "reachable");
        }), host);

        connection.connect();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        connection.connect();

        assertEquals("IDEA MCP connection is already in progress", host.lastStatus());
        assertEquals(1, requests.get());
        assertTrue(connection.inProgress());
        assertEquals("IDEA MCP: connecting…", host.statuses().get(0));

        finish.countDown();
        host.runNextUiUpdate();
        assertFalse(connection.inProgress());
        assertEquals(1, requests.get());
    }

    @Test
    void aFailedConnectionReachesTheStatusAndAllowsARetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        RecordingHost host = new RecordingHost(List.of());
        IdeaConnection connection = new IdeaConnection(new BlockingConnection(() -> {
            requests.incrementAndGet();
            throw new IllegalStateException("socket closed");
        }), host);

        connection.connect();
        host.runNextUiUpdate();

        assertEquals("IDEA MCP: error, connection failed; check the configured HTTP Stream Config and retry",
                host.lastStatus());
        assertFalse(connection.inProgress());

        connection.connect();
        host.runNextUiUpdate();

        assertEquals(2, requests.get());
        assertFalse(connection.inProgress());
    }

    @Test
    void successReportsTheStateAndRefreshesTheRunAndProblemsWindowsOnly() throws Exception {
        RecordingWindow run = new RecordingWindow("run");
        RecordingWindow problems = new RecordingWindow("problems");
        RecordingWindow terminal = new RecordingWindow("terminal");
        RecordingHost host = new RecordingHost(List.of(run, problems, terminal));
        IdeaConnection connection = new IdeaConnection(new BlockingConnection(
                () -> new IdeaMcpConnection.Snapshot("CONNECTED", "IntelliJ IDEA MCP server reachable")), host);

        connection.connect();
        host.runNextUiUpdate();

        assertEquals("IDEA MCP: connected, IntelliJ IDEA MCP server reachable", host.lastStatus());
        assertEquals(List.of(KeyActions.RUN_REFRESH), run.actions());
        assertEquals(List.of(KeyActions.PROBLEMS_REFRESH), problems.actions());
        assertEquals(List.of(), terminal.actions());
    }

    @Test
    void blankDetailLeavesOnlyTheStateInTheStatus() throws Exception {
        RecordingHost host = new RecordingHost(List.of());
        IdeaConnection connection = new IdeaConnection(new BlockingConnection(
                () -> new IdeaMcpConnection.Snapshot("DISCONNECTED", " ")), host);

        connection.connect();
        host.runNextUiUpdate();

        assertEquals("IDEA MCP: disconnected", host.lastStatus());
    }

    @Test
    void missingConnectionIsReportedWithoutStartingAnyRequest() {
        RecordingHost host = new RecordingHost(List.of());
        IdeaConnection connection = new IdeaConnection(null, host);

        connection.connect();

        assertEquals("IDEA MCP connection control is unavailable", host.lastStatus());
        assertFalse(connection.inProgress());
        assertEquals(1, host.statuses().size());
    }

    private interface Reconnect {

        IdeaMcpConnection.Snapshot call() throws Exception;
    }

    private record BlockingConnection(Reconnect reconnect) implements IdeaMcpConnection {

        @Override
        public Snapshot connectionStatus() {
            return new Snapshot("DISCONNECTED", "not connected yet");
        }

        @Override
        public Snapshot reconnectFromUserAction() {
            try {
                return reconnect.call();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class RecordingHost implements IdeaConnection.Host {

        private final List<String> statuses = new CopyOnWriteArrayList<>();
        private final Queue<Runnable> uiUpdates = new ConcurrentLinkedQueue<>();
        private final List<ToolWindow> windows;

        RecordingHost(List<ToolWindow> windows) {
            this.windows = List.copyOf(windows);
        }

        @Override
        public void status(String message) {
            statuses.add(message);
        }

        @Override
        public void runOnUi(Runnable update) {
            uiUpdates.add(update);
        }

        @Override
        public List<ToolWindow> toolWindows() {
            return windows;
        }

        List<String> statuses() {
            return List.copyOf(statuses);
        }

        String lastStatus() {
            return statuses.get(statuses.size() - 1);
        }

        void runNextUiUpdate() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (uiUpdates.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            Runnable update = uiUpdates.poll();
            assertTrue(update != null, "the connection never reported back to the UI thread");
            update.run();
        }
    }

    private static final class RecordingWindow extends FakeToolWindow {

        private final List<String> actions = new ArrayList<>();

        RecordingWindow(String id) {
            super(id, id, 'x', 0, id);
        }

        @Override
        public boolean performAction(String actionId) {
            actions.add(actionId);
            return true;
        }

        List<String> actions() {
            return List.copyOf(actions);
        }
    }
}
