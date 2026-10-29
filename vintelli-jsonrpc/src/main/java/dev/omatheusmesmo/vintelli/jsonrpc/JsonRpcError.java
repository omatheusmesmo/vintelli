package dev.omatheusmesmo.vintelli.jsonrpc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JSON-RPC 2.0 error object carried by error responses and by handler thrown
 * {@link JsonRpcRequestException}.
 *
 * @param code    error code, for example {@link #PARSE_ERROR} or {@link #INTERNAL_ERROR}
 * @param message human readable description
 * @param data    optional structured detail, may be null
 */
public record JsonRpcError(int code, String message, Object data) {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int REQUEST_CANCELLED = -32800;

    public JsonRpcError {
        Objects.requireNonNull(message, "error message");
    }

    public static JsonRpcError of(int code, String message) {
        return new JsonRpcError(code, message, null);
    }

    public static JsonRpcError fromTree(Object value) {
        if (!(value instanceof Map<?, ?> tree)) {
            throw new JsonRpcProtocolException("error must be a JSON object");
        }
        int code = extractCode(tree.get("code"));
        Object rawMessage = tree.get("message");
        if (!(rawMessage instanceof String message)) {
            throw new JsonRpcProtocolException("error.message must be a string");
        }
        return new JsonRpcError(code, message, tree.get("data"));
    }

    private static int extractCode(Object value) {
        if (!(value instanceof Number number) || number.longValue() != number.intValue()) {
            throw new JsonRpcProtocolException("error.code must be a 32 bit integer");
        }
        return number.intValue();
    }

    public Map<String, Object> asTree() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("code", Integer.valueOf(code));
        tree.put("message", message);
        if (data != null) {
            tree.put("data", data);
        }
        return tree;
    }
}
