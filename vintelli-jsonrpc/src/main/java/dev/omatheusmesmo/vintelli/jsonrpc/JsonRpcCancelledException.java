package dev.omatheusmesmo.vintelli.jsonrpc;

/**
 * Raised when a locally issued request was cancelled through
 * {@link JsonRpcEndpoint#cancelRequest(JsonRpcId)}. An inbound cancellation
 * notification never cancels a local request: id spaces are per direction, so a
 * peer cancellation only concerns requests the peer sent.
 */
public final class JsonRpcCancelledException extends JsonRpcTransportException {

    public JsonRpcCancelledException() {
        super("request cancelled");
    }
}
