package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Byte framing applied around one UTF-8 JSON-RPC payload on a byte stream.
 *
 * <p>Two stdio framings exist in the wild for the consumers of this module:</p>
 *
 * <ul>
 * <li>{@link ContentLengthFraming}: LSP style headers, used by language servers.</li>
 * <li>{@link LineDelimitedFraming}: newline delimited JSON used by the ACP stdio
 * transport per https://agentclientprotocol.com/protocol/v1/transports, where the
 * JSON-RPC messages MUST be UTF-8 encoded and MUST NOT contain embedded newlines.</li>
 * </ul>
 *
 * <p>Payload bytes stay untouched in both directions: splitting happens on bytes,
 * UTF-8 decoding belongs to the JSON layer, so multi byte characters survive.
 * Implementations must never buffer without bound: a frame larger than the
 * configured maximum aborts the stream instead of growing memory.</p>
 */
public interface JsonRpcFraming {

    /**
     * Reads exactly one frame and returns its payload bytes, or null on a clean end
     * of stream before any byte of the frame was received. A framing fault (bad or
     * oversized header, mid frame end of stream) raises {@link IOException} and
     * leaves the stream unusable.
     */
    byte[] readFrame(InputStream in) throws IOException;

    /**
     * Writes exactly one frame carrying the payload. Implementations do not flush;
     * callers flush when the message is complete.
     */
    void writeFrame(OutputStream out, byte[] payload) throws IOException;
}
