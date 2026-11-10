package dev.omatheusmesmo.vintelli.tui;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;

/**
 * Runs the user requested IntelliJ IDEA MCP reconnect off the render thread and reports its progress through
 * the {@link Host}. A request while one runs is ignored, and the completion goes back to the UI thread through
 * {@link Host#runOnUi(Runnable)}.
 */
final class IdeaConnection {

    interface Host {

        void status(String message);

        void runOnUi(Runnable update);

        List<ToolWindow> toolWindows();
    }

    private final IdeaMcpConnection connection;
    private final Host host;
    private final AtomicBoolean inProgress = new AtomicBoolean();

    IdeaConnection(IdeaMcpConnection connection, Host host) {
        this.connection = connection;
        this.host = host;
    }

    boolean inProgress() {
        return inProgress.get();
    }

    void connect() {
        if (connection == null) {
            host.status("IDEA MCP connection control is unavailable");
            return;
        }
        if (!inProgress.compareAndSet(false, true)) {
            host.status("IDEA MCP connection is already in progress");
            return;
        }
        host.status("IDEA MCP: connecting…");
        Thread.ofVirtual().start(() -> {
            IdeaMcpConnection.Snapshot result;
            try {
                result = connection.reconnectFromUserAction();
            } catch (RuntimeException failure) {
                result = new IdeaMcpConnection.Snapshot("ERROR",
                        "connection failed; check the configured HTTP Stream Config and retry");
            }
            IdeaMcpConnection.Snapshot completed = result;
            host.runOnUi(() -> settle(completed));
        });
    }

    private void settle(IdeaMcpConnection.Snapshot completed) {
        inProgress.set(false);
        host.status("IDEA MCP: " + completed.state().toLowerCase(Locale.ROOT).replace('_', ' ')
                + (completed.detail().isBlank() ? "" : ", " + completed.detail()));
        for (ToolWindow window : host.toolWindows()) {
            if ("run".equals(window.id())) {
                window.performAction(KeyActions.RUN_REFRESH);
            } else if ("problems".equals(window.id())) {
                window.performAction(KeyActions.PROBLEMS_REFRESH);
            }
        }
    }
}
