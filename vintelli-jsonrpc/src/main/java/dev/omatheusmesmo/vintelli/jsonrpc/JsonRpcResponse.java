package dev.omatheusmesmo.vintelli.jsonrpc;

import java.util.Objects;

/**
 * A response to a request. Exactly one of {@code result} or {@code error} is set:
 * an error response carries only the error, a successful response carries
 * {@code result} which may itself be JSON null.
 *
 * <p>The id is non null for every response this module writes or builds through the
 * factory methods. A received response whose id is null is allowed only as an error
 * response reporting a failure before the id could be matched, per JSON-RPC 2.0: the
 * transport surfaces such a response through its error listener.</p>
 *
 * @param id     identifier copied from the request this answers, null only for a
 *               received error response with no matchable id
 * @param result success payload, null on error and null when the handler returned null
 * @param error  error payload, non null only on error responses
 */
public record JsonRpcResponse(JsonRpcId id, Object result, JsonRpcError error) implements JsonRpcMessage {

    public JsonRpcResponse {
        if (error != null && result != null) {
            throw new IllegalArgumentException("response must not carry result and error together");
        }
        if (id == null && error == null) {
            throw new IllegalArgumentException(
                    "a response without an id must carry an error");
        }
    }

    public static JsonRpcResponse success(JsonRpcId id, Object result) {
        Objects.requireNonNull(id, "response id");
        return new JsonRpcResponse(id, result, null);
    }

    public static JsonRpcResponse failure(JsonRpcId id, JsonRpcError error) {
        Objects.requireNonNull(id, "response id");
        return new JsonRpcResponse(id, null, error);
    }
}
