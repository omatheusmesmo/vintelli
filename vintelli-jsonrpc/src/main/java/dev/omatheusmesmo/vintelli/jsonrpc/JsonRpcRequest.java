package dev.omatheusmesmo.vintelli.jsonrpc;

import java.util.Objects;

/**
 * A request that expects a response with the same {@code id}.
 *
 * @param id     stable request identifier, assigned by the sending side
 * @param method method name, for example {@code initialize}
 * @param params structured parameters (JSON object or array as plain maps and lists),
 *               may be null
 */
public record JsonRpcRequest(JsonRpcId id, String method, Object params) implements JsonRpcMessage {

    public JsonRpcRequest {
        Objects.requireNonNull(id, "request id");
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("request method must not be blank");
        }
    }
}
