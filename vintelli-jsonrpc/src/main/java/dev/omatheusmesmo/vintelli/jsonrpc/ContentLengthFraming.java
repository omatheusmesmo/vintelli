package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Content-Length framing used by the Language Server Protocol base protocol over
 * stdio: ASCII header lines terminated by CRLF, an empty header line, then exactly
 * {@code Content-Length} payload bytes as read, without any charset round trip:
 * the payload carries its UTF-8 bytes through unchanged.
 *
 * <p>The field name must be exactly {@code Content-Length} case insensitively, so
 * headers like {@code Content-Length-Extra} are unknown headers and are skipped.
 * A second {@code Content-Length} header is a fault; conflicting duplicates must not
 * select one value silently. Unknown headers are skipped, mirroring the LSP base
 * protocol, which allows an optional charset field next to the length.</p>
 *
 * @param maxFrameSizeBytes largest accepted payload size in bytes; a larger declared
 *                          length aborts the stream instead of buffering it
 */
public record ContentLengthFraming(long maxFrameSizeBytes) implements JsonRpcFraming {

    private static final String CONTENT_LENGTH_HEADER = "Content-Length";

    public static final long DEFAULT_MAX_FRAME_SIZE_BYTES = 16L * 1024L * 1024L;

    public static final ContentLengthFraming STANDARD =
            new ContentLengthFraming(DEFAULT_MAX_FRAME_SIZE_BYTES);

    public ContentLengthFraming {
        if (maxFrameSizeBytes < 1) {
            throw new IllegalArgumentException("maxFrameSizeBytes must be positive");
        }
    }

    @Override
    public byte[] readFrame(InputStream in) throws IOException {
        FrameReader reader = new FrameReader(in);
        try {
            int contentLength = readHeaders(reader);
            return reader.readFully(contentLength);
        } catch (FrameReader.StreamEnded endOfStream) {
            if (!reader.anyByteRead()) {
                return null;
            }
            throw new IOException("stream ended mid frame", endOfStream);
        }
    }

    private int readHeaders(FrameReader reader) throws IOException {
        int contentLength = -1;
        int headerLines = 0;
        while (true) {
            byte[] lineBytes = reader.readLineBytes(FrameReader.MAX_HEADER_LINE_BYTES);
            if (headerLines > FrameReader.MAX_HEADER_LINES) {
                throw new IOException("header block exceeds "
                        + FrameReader.MAX_HEADER_LINES + " lines");
            }
            String line = FrameReader.asHeaderLine(lineBytes);
            if (isHeaderTerminator(line)) {
                if (contentLength < 0) {
                    throw new IOException("missing Content-Length header");
                }
                return contentLength;
            }
            headerLines++;
            contentLength = applyHeader(line, contentLength);
        }
    }

    private static boolean isHeaderTerminator(String line) {
        return line.isEmpty() || (line.length() == 1 && line.charAt(0) == '\r');
    }

    private int applyHeader(String line, int currentContentLength) throws IOException {
        int colon = line.indexOf(':');
        if (colon < 1) {
            throw new IOException("malformed header line: " + line.trim());
        }
        String name = line.substring(0, colon).trim();
        if (!name.equalsIgnoreCase(CONTENT_LENGTH_HEADER)) {
            return currentContentLength;
        }
        if (currentContentLength >= 0) {
            throw new IOException("duplicate Content-Length header");
        }
        String rawLength = line.substring(colon + 1).replace("\r", "").trim();
        return parseContentLength(rawLength);
    }

    private int parseContentLength(String rawLength) throws IOException {
        long parsed;
        try {
            parsed = Long.parseLong(rawLength);
        } catch (NumberFormatException malformed) {
            throw new IOException("Content-Length is not a number: " + rawLength);
        }
        if (parsed < 0 || parsed > maxFrameSizeBytes || parsed > Integer.MAX_VALUE) {
            throw new IOException("Content-Length " + parsed
                    + " outside the accepted frame range up to " + maxFrameSizeBytes);
        }
        return (int) parsed;
    }

    @Override
    public void writeFrame(OutputStream out, byte[] payload) throws IOException {
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        out.write(header);
        out.write(payload);
    }
}
