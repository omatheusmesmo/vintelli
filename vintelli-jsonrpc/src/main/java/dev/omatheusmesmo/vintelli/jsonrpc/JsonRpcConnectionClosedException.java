package dev.omatheusmesmo.vintelli.jsonrpc;

/**
 * Raised when the underlying stream ended or failed and the endpoint stopped working.
 *
 * <p>Delivery of this failure to a pending request does not roll back anything the
 * peer already processed; the request outcome is simply unknown.</p>
 */
public final class JsonRpcConnectionClosedException extends JsonRpcTransportException {

    public JsonRpcConnectionClosedException(String message) {
        super(message);
    }

    public JsonRpcConnectionClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
