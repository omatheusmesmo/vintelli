package dev.omatheusmesmo.vintelli.jsonrpc;

/**
 * Raised when a single message payload is malformed JSON or violates the JSON-RPC
 * envelope rules.
 *
 * <p>The frame carrying the payload has already been consumed from the stream when
 * this is thrown, so receivers may drop the message, report it, and keep reading:
 * framing integrity is not harmed by payload errors. This is unlike framing level
 * faults (bad or oversized headers), which abort the connection because the stream
 * cannot be resynchronized.</p>
 *
 * <p>When the payload looked like a request and its id could be recovered, the
 * recoverable id is carried along so the receiver can answer it with the
 * {@code INVALID_REQUEST} error code, as JSON-RPC 2.0 requires for requests that
 * cannot be handled.</p>
 */
public final class JsonRpcProtocolException extends JsonRpcTransportException {

    private final JsonRpcId recoverableId;

    public JsonRpcProtocolException(String message) {
        super(message);
        this.recoverableId = null;
    }

    public JsonRpcProtocolException(String message, Throwable cause) {
        super(message, cause);
        this.recoverableId = null;
    }

    public JsonRpcProtocolException(String message, JsonRpcId recoverableId) {
        super(message);
        this.recoverableId = recoverableId;
    }

    /**
     * Recovered id of a request shaped invalid message, or null when no id could be
     * recovered or the message was not a request.
     */
    public JsonRpcId recoverableId() {
        return recoverableId;
    }
}
