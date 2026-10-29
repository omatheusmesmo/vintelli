package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bidirectional JSON-RPC channel over one framing: sends messages framed on the
 * output stream and receives messages framed on the input stream. Writes are
 * serialized internally, so a reader loop and concurrent responding tasks may share
 * one instance.
 *
 * <p>The owned streams are wrapped in buffered variants; end of stream is reported
 * as a null receive, framing faults as {@link IOException}, and malformed payloads as
 * {@link JsonRpcProtocolException}. A malformed payload does not damage the framing:
 * the frame bytes were already consumed, so the connection stays usable. Callers
 * decide whether to drop such messages or close.</p>
 *
 * <p>A failed partial write poisons the output stream, so the first send that fails
 * marks the connection broken: every later send fails immediately instead of
 * interleaving into the half frame left on the wire.</p>
 */
public final class JsonRpcConnection implements AutoCloseable {

    private final JsonRpcFraming framing;
    private final InputStream in;
    private final OutputStream out;
    private final Object sendLock = new Object();
    private volatile boolean closed;

    public JsonRpcConnection(JsonRpcFraming framing, InputStream in, OutputStream out) {
        if (framing == null || in == null || out == null) {
            throw new IllegalArgumentException("framing and streams must not be null");
        }
        this.framing = framing;
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
        this.out = out instanceof BufferedOutputStream ? out : new BufferedOutputStream(out);
    }

    /**
     * Reads the next framed message, or null on a clean end of stream. A malformed
     * payload arrives as {@link JsonRpcProtocolException} after the whole frame was
     * consumed from the stream, so the next call continues at the following frame.
     */
    public JsonRpcMessage receive() throws IOException {
        byte[] frame = framing.readFrame(in);
        if (frame == null) {
            return null;
        }
        return JsonRpcMessageCodec.decode(new String(frame, StandardCharsets.UTF_8));
    }

    public void send(JsonRpcMessage message) throws IOException {
        byte[] payload = JsonRpcMessageCodec.encode(message).getBytes(StandardCharsets.UTF_8);
        synchronized (sendLock) {
            if (closed) {
                throw new IOException("connection already closed");
            }
            try {
                framing.writeFrame(out, payload);
                out.flush();
            } catch (IOException writeFailure) {
                markBroken();
                throw writeFailure;
            }
        }
    }

    /**
     * Releases both streams. Failures while closing are swallowed on purpose: close
     * is best effort, it cannot recover either stream, and it must never mask the
     * reason that triggered it. The output stream is released without holding
     * {@code sendLock}, so a close during a stuck writer cannot deadlock.
     */
    @Override
    public void close() {
        closed = true;
        try (OutputStream ignored = out) {
        } catch (IOException ignoredCloseFailure) {
        }
        try (InputStream ignored = in) {
        } catch (IOException ignoredCloseFailure) {
        }
    }

    private void markBroken() {
        closed = true;
        try (OutputStream ignored = out) {
        } catch (IOException ignoredCloseFailure) {
        }
    }
}
