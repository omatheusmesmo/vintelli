package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Byte reading helpers shared by the frame implementations. All operations are
 * bounded so a broken peer can never make a receiver allocate without limit. Line
 * reads consume one byte per call and body reads use an exact length read, so
 * neither can pull bytes past the current frame: callers can keep reading the
 * stream right where the frame ended.
 */
final class FrameReader {

    static final int MAX_HEADER_LINE_BYTES = 8192;
    static final int MAX_HEADER_LINES = 64;

    /**
     * Raised when the stream reached end of stream. Receivers translate this into a
     * clean stop only when no byte of the current frame had been consumed yet.
     */
    static final class StreamEnded extends IOException {

        StreamEnded() {
            super("stream ended");
        }
    }

    private final InputStream in;
    private boolean anyByteRead;

    FrameReader(InputStream in) {
        this.in = in;
    }

    boolean anyByteRead() {
        return anyByteRead;
    }

    /**
     * Reads one LF terminated line as raw bytes, terminator excluded. A trailing CR
     * byte is kept; callers strip it when they treat the line as text. The limit
     * counts the line bytes without the terminator.
     */
    byte[] readLineBytes(int maxBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        while (true) {
            int current = in.read();
            if (current < 0) {
                if (!anyByteRead) {
                    throw new StreamEnded();
                }
                throw new IOException("stream ended inside a line");
            }
            anyByteRead = true;
            if (current == '\n') {
                return line.toByteArray();
            }
            if (line.size() >= maxBytes) {
                throw new IOException("line exceeds the " + maxBytes + " byte limit");
            }
            line.write(current);
        }
    }

    /**
     * Reads exactly {@code length} bytes, buffered: the body is filled in a single
     * chunk read. End of stream inside the body raises an end of stream fault, since
     * the headers already consumed bytes.
     */
    byte[] readFully(int length) throws IOException {
        if (length == 0) {
            return new byte[0];
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            throw new IOException("stream ended mid body after " + payload.length
                    + " of " + length + " bytes");
        }
        return payload;
    }

    /**
     * Header line decoder: header lines are ASCII per the LSP base protocol, so
     * Latin-1 keeps every observed byte exactly.
     */
    static String asHeaderLine(byte[] lineBytes) {
        return new String(lineBytes, StandardCharsets.ISO_8859_1);
    }
}
