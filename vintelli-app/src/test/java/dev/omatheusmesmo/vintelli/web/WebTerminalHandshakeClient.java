package dev.omatheusmesmo.vintelli.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Small WebSocket client for the terminal surface: builds handshakes (with or
 * without credentials and origin) and either accepts the upgrade and records
 * every frame the session streams into a {@link WebTerminalScreen}, or reports
 * the HTTP status that rejected the handshake, so assertions read the rendered
 * rows and the raw ANSI stream.
 */
final class WebTerminalHandshakeClient implements WebSocket.Listener {

    private final WebTerminalScreen screen = new WebTerminalScreen();
    private final Object frameLock = new Object();
    private long frameSequence;
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile int closeCode = -1;
    private volatile WebSocket connected;

    static URI terminalOf(URI base) {
        String scheme = base.getScheme().equals("https") ? "wss" : "ws";
        return URI.create(scheme + "://" + base.getAuthority() + base.getPath());
    }

    static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    WebSocket connect(URI base, String authorization, String origin) {
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        WebSocket socket;
        try {
            socket = builder.buildAsync(terminalOf(base), this)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WebSocketHandshakeException handshake) {
                throw new UpgradeFailure(handshake.getResponse().statusCode());
            }
            throw new IllegalStateException("handshake failed with something other than an HTTP status", cause);
        }
        connected = socket;
        return socket;
    }

    String screen() {
        return screen.text();
    }

    int lastContentRow() {
        return screen.lastContentRow();
    }

    String raw() {
        return screen.raw();
    }

    void sendInput(String keys) {
        WebSocket socket = connected;
        if (socket == null || socket.isInputClosed()) {
            throw new IllegalStateException("terminal closed");
        }
        socket.sendBinary(ByteBuffer.wrap(keys.getBytes(StandardCharsets.UTF_8)), true);
    }

    void sendControl(String message) {
        WebSocket socket = connected;
        if (socket == null) {
            throw new IllegalStateException("terminal closed");
        }
        socket.sendText(message, true);
    }

    boolean awaitFrame(long timeoutMillis) throws InterruptedException {
        long baseline;
        synchronized (frameLock) {
            baseline = frameSequence;
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (frameLock) {
            while (frameSequence == baseline && System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                frameLock.wait(Math.min(remaining, 50));
            }
            return frameSequence != baseline;
        }
    }

    boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        return closed.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    void close() {
        WebSocket socket = connected;
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof IOException failure)) {
                throw e;
            }
            if (closed.getCount() > 0) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    int closeCode() {
        return closeCode;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closeCode = statusCode;
        closed.countDown();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.countDown();
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        record(StandardCharsets.UTF_8.decode(data.duplicate()).toString());
        return WebSocket.Listener.super.onBinary(webSocket, data, last);
    }

    private void record(String decoded) {
        screen.write(decoded);
        synchronized (frameLock) {
            frameSequence++;
            frameLock.notifyAll();
        }
    }

    /**
     * Signals an upgrade that ended with an HTTP status instead of 101.
     */
    static final class UpgradeFailure extends RuntimeException {

        private final int status;

        UpgradeFailure(int status) {
            super("handshake rejected with " + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
