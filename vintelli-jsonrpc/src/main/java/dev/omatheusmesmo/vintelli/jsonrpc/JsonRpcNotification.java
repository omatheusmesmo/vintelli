package dev.omatheusmesmo.vintelli.jsonrpc;

/**
 * A message sent without expecting any response.
 *
 * @param method method name, for example {@code initialized} or {@code $/cancelRequest}
 * @param params structured parameters (JSON object or array as plain maps and lists),
 *               may be null
 */
public record JsonRpcNotification(String method, Object params) implements JsonRpcMessage {

    public JsonRpcNotification {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("notification method must not be blank");
        }
    }
}
