package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Newline delimited framing used by the ACP stdio transport: every message is a
 * single JSON object terminated by LF, and the ACP specification at
 * https://agentclientprotocol.com/protocol/v1/transports requires the whole
 * JSON-RPC message to be UTF-8 encoded and forbids embedded newlines. A trailing CR
 * byte before the LF is tolerated so Windows style peers interoperate. Blank lines
 * are skipped. A final line without a terminator is a fault, as is an oversized
 * line.
 *
 * <p>Bytes are split on the LF only: the frame is decoded as UTF-8 at the JSON
 * layer, so multi byte characters survive exactly.</p>
 *
 * @param maxFrameSizeBytes largest accepted line size in bytes; a longer line aborts
 *                          the stream instead of buffering it
 */
public record LineDelimitedFraming(long maxFrameSizeBytes) implements JsonRpcFraming {

    public static final long DEFAULT_MAX_FRAME_SIZE_BYTES = 16L * 1024L * 1024L;

    public static final LineDelimitedFraming STANDARD =
            new LineDelimitedFraming(DEFAULT_MAX_FRAME_SIZE_BYTES);

    public LineDelimitedFraming {
        if (maxFrameSizeBytes < 1) {
            throw new IllegalArgumentException("maxFrameSizeBytes must be positive");
        }
    }

    @Override
    public byte[] readFrame(InputStream in) throws IOException {
        FrameReader reader = new FrameReader(in);
        int maxLineBytes = (int) Math.min(maxFrameSizeBytes, Integer.MAX_VALUE);
        while (true) {
            byte[] line;
            try {
                line = reader.readLineBytes(maxLineBytes);
            } catch (FrameReader.StreamEnded endOfStream) {
                if (!reader.anyByteRead()) {
                    return null;
                }
                throw new IOException("stream ended mid frame", endOfStream);
            }
            byte[] withoutCarriage = stripCarriageReturn(line);
            if (withoutCarriage.length > 0) {
                return withoutCarriage;
            }
        }
    }

    private static byte[] stripCarriageReturn(byte[] line) {
        if (line.length > 0 && line[line.length - 1] == '\r') {
            byte[] stripped = new byte[line.length - 1];
            System.arraycopy(line, 0, stripped, 0, stripped.length);
            return stripped;
        }
        return line;
    }

    @Override
    public void writeFrame(OutputStream out, byte[] payload) throws IOException {
        for (byte current : payload) {
            if (current == '\n') {
                throw new IllegalArgumentException(
                        "line delimited payload must not contain a newline byte");
            }
        }
        out.write(payload);
        out.write('\n');
    }
}
