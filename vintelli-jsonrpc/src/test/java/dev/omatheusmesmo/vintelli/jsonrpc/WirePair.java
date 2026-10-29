package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * In-memory full duplex pipe pair between two {@link JsonRpcConnection} endpoints,
 * standing in for a real stdio child process. The raw server to client direction
 * lets tests inject malformed frames the endpoint never formed, the tapping reader
 * records the client side bytes so tests can observe the exact answers an endpoint
 * wrote.
 */
final class WirePair implements AutoCloseable {

    private final MemoryPipe clientToServer = new MemoryPipe();
    private final MemoryPipe serverToClient = new MemoryPipe();
    private final ByteArrayOutputStream serverInputTap = new ByteArrayOutputStream();

    JsonRpcConnection clientSide(JsonRpcFraming framing) {
        return new JsonRpcConnection(framing, serverToClient.reader(), clientToServer.writer());
    }

    JsonRpcConnection serverSide(JsonRpcFraming framing) {
        return new JsonRpcConnection(framing, tapServerInput(), serverToClient.writer());
    }

    void writeRawFromServer(byte[] bytes) throws IOException {
        OutputStream rawWriter = serverToClient.writer();
        rawWriter.write(bytes);
        rawWriter.flush();
    }

    /**
     * Simulates a peer that stops writing: the client side reads a clean end of
     * stream once this returns.
     */
    void stopServerWriter() {
        try (OutputStream stoppedWriter = serverToClient.writer()) {
        } catch (IOException stopFailure) {
            /**
             * Closing a memory pipe never fails: the end marker is queued instead.
             */
        }
    }

    void writeRawFromClient(byte[] bytes) throws IOException {
        OutputStream rawWriter = clientToServer.writer();
        rawWriter.write(bytes);
        rawWriter.flush();
    }

    byte[] tappedServerInput() {
        return serverInputTap.toByteArray();
    }

    private InputStream tapServerInput() {
        InputStream source = clientToServer.reader();
        return new InputStream() {

            @Override
            public int read() throws IOException {
                int value = source.read();
                if (value >= 0) {
                    serverInputTap.write(value);
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = source.read(buffer, offset, length);
                if (read > 0) {
                    serverInputTap.write(buffer, offset, read);
                }
                return read;
            }
        };
    }

    /**
     * Releases the reader ends of both pipes. Best effort: the pipe pair is a test
     * fixture, so a failing close never masks the real assertion outcome.
     */
    @Override
    public void close() {
        for (int side = 0; side < 2; side++) {
            InputStream reader = side == 0 ? serverToClient.reader() : clientToServer.reader();
            try (InputStream released = reader) {
            } catch (IOException failedClose) {
                /**
                 * The fixture reader close is a no-op for the local memory pipe: the
                 * queued end marks the queue as drained and never fails here.
                 */
            }
        }
    }
}
