package dev.omatheusmesmo.vintelli.jsonrpc;

import java.util.Objects;

/**
 * Raised when the remote peer answered a request with a JSON-RPC error response.
 */
public final class JsonRpcRemoteException extends JsonRpcTransportException {

    private final JsonRpcError error;

    public JsonRpcRemoteException(JsonRpcError error) {
        super("remote error " + Objects.requireNonNull(error, "error").code()
                + ": " + error.message());
        this.error = error;
    }

    public JsonRpcError error() {
        return error;
    }
}
