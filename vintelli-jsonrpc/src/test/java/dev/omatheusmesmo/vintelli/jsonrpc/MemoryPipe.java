package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One direction of an in-memory byte pipe whose endpoints tolerate writer threads
 * dying between writes, which JVM piped streams do not. The writer never blocks; the
 * reader blocks until bytes, end of stream, or a reader close arrive.
 */
final class MemoryPipe {

    private static final byte[] END_OF_STREAM = new byte[0];
    private static final long READ_POLL_MILLIS = 50L;

    private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private volatile boolean writerClosed;
    private volatile boolean readerClosed;

    OutputStream writer() {
        return new OutputStream() {

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (writerClosed) {
                    throw new IOException("pipe writer already closed");
                }
                if (offset < 0 || length < 0 || offset + length > buffer.length) {
                    throw new IOException("offset or length out of range");
                }
                byte[] copy = new byte[length];
                System.arraycopy(buffer, offset, copy, 0, length);
                chunks.add(copy);
            }

            @Override
            public void close() {
                if (!writerClosed) {
                    writerClosed = true;
                    chunks.add(END_OF_STREAM);
                }
            }
        };
    }

    InputStream reader() {
        return new InputStream() {

            private byte[] current = null;
            private int position = 0;

            @Override
            public int read() throws IOException {
                while (true) {
                    if (readerClosed) {
                        return -1;
                    }
                    if (current == null || position >= current.length) {
                        current = nextChunk();
                        if (current == null) {
                            return -1;
                        }
                        position = 0;
                    }
                    if (position < current.length) {
                        return current[position++] & 0xFF;
                    }
                }
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (readerClosed) {
                    return -1;
                }
                if (current == null || position >= current.length) {
                    current = nextChunk();
                    if (current == null) {
                        return -1;
                    }
                    position = 0;
                }
                int readable = Math.min(length, current.length - position);
                System.arraycopy(current, position, target, offset, readable);
                position += readable;
                return readable;
            }

            @Override
            public void close() {
                readerClosed = true;
                chunks.add(END_OF_STREAM);
            }

            private byte[] nextChunk() throws IOException {
                try {
                    while (true) {
                        if (readerClosed) {
                            return null;
                        }
                        byte[] chunk = chunks.poll(READ_POLL_MILLIS, TimeUnit.MILLISECONDS);
                        if (chunk == END_OF_STREAM) {
                            writerClosed = true;
                            return null;
                        }
                        if (chunk != null) {
                            return chunk;
                        }
                        if (writerClosed && chunks.isEmpty()) {
                            return null;
                        }
                    }
                } catch (InterruptedException interruptedRead) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for pipe bytes", interruptedRead);
                }
            }
        };
    }
}
