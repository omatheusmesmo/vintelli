package dev.omatheusmesmo.vintelli.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.web.WebTerminalOutput;
import dev.omatheusmesmo.vintelli.tui.web.WebTerminalSessions;
import io.quarkus.security.Authenticated;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnBinaryMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * WebSocket surface of the TUI shell: one session per connection renders the
 * same shell the local TUI renders, so the shell state can be watched and
 * driven from a terminal in a browser. Guided by {@link WebTerminalGate}:
 * closed by default (the upgrade answers 404 until
 * {@code vintelli.web.enabled=true}), reachable only from the hosts and
 * origins the shared loopback guard accepts, and only from a client that
 * passes HTTP basic authentication ({@code @Authenticated} on the upgrade).
 *
 * <p>The protocol is the one a real terminal would carry. Binary frames are
 * key bytes in terminal encoding on the way in and ANSI frames of the rendered
 * screen on the way out, so an xterm.js style client needs no server specific
 * logic. The only text frame is {@code resize COLUMNSxROWS}, which changes the
 * session viewport; the session clamps both values, so no message can ask for
 * an absurd buffer. Nothing else on this surface writes shell state: key input
 * is the one path, exactly what the local TUI accepts.
 */
@WebSocket(path = "/tui/ws", endpointId = WebTerminalGate.ENDPOINT_ID)
@Authenticated
@Singleton
public final class WebTerminalEndpoint {

    private static final CloseReason SESSION_FAILED = new CloseReason(4000, "terminal failed to start");
    private static final CloseReason SESSION_ENDED = new CloseReason(4001, "terminal ended");
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final WebTerminalSessions sessions;

    @Inject
    WebTerminalEndpoint(WebTerminalSessions sessions) {
        this.sessions = sessions;
    }

    @OnOpen
    public Uni<Void> open(WebSocketConnection connection) {
        WebTerminalOutput output = ansi -> {
            if (connection.isClosed()) {
                throw new IllegalStateException("terminal connection closed");
            }
            connection.sendBinary(ansi.getBytes(StandardCharsets.UTF_8)).await().atMost(SEND_TIMEOUT);
        };
        boolean started = sessions.start(connection.id(), output, () -> {
            sessions.remove(connection.id());
            if (connection.isOpen()) {
                connection.closeAndAwait(SESSION_ENDED);
            }
        });
        if (!started) {
            sessions.release();
            return connection.close(SESSION_FAILED);
        }
        return Uni.createFrom().voidItem();
    }

    @OnBinaryMessage
    public void onInput(WebSocketConnection connection, byte[] bytes) {
        sessions.input(connection.id(), bytes);
    }

    @OnTextMessage
    public void onResize(WebSocketConnection connection, String message) {
        ResizeMessage.parse(message).ifPresent(size -> sessions.resize(connection.id(), size.columns(), size.rows()));
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        sessions.stop(connection.id());
    }

    @OnError
    public void onError(WebSocketConnection connection, Throwable failure) {
        sessions.stop(connection.id());
    }

    /**
     * The one text frame the surface accepts: {@code resize COLUMNSxROWS}.
     * Anything else is ignored, so a stray client message can never drive the
     * session beyond its viewport.
     */
    record ResizeMessage(int columns, int rows) {

        private static final String PREFIX = "resize ";

        static Optional<ResizeMessage> parse(String message) {
            if (message == null) {
                return Optional.empty();
            }
            String trimmed = message.trim();
            if (!trimmed.startsWith(PREFIX) || trimmed.length() <= PREFIX.length()) {
                return Optional.empty();
            }
            String[] parts = trimmed.substring(PREFIX.length()).split("x");
            if (parts.length != 2) {
                return Optional.empty();
            }
            try {
                int columns = Integer.parseInt(parts[0].trim());
                int rows = Integer.parseInt(parts[1].trim());
                if (columns <= 0 || rows <= 0) {
                    return Optional.empty();
                }
                return Optional.of(new ResizeMessage(columns, rows));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }
}
