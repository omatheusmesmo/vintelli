package dev.omatheusmesmo.vintelli.jsonrpc;

import java.util.Objects;

/**
 * Raised when a request handler fails; the error object controls the response sent
 * back to the peer, and it never contains local exception detail.
 *
 * <p>Any other exception escaping the handler is reported to the peer as the generic
 * internal error with a fixed message, and reported locally through the error
 * listener, so handlers that need a specific code must throw this type.</p>
 */
public final class JsonRpcRequestException extends JsonRpcTransportException {

    private final JsonRpcError error;

    public JsonRpcRequestException(JsonRpcError error) {
        super("request failed " + Objects.requireNonNull(error, "error").code()
                + ": " + error.message());
        this.error = error;
    }

    public JsonRpcError error() {
        return error;
    }
}
