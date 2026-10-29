package dev.omatheusmesmo.vintelli.jsonrpc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes and decodes JSON-RPC 2.0 messages between the message records and their
 * single line JSON text form, on top of the Jackson streaming API: reflection free
 * by construction, nothing but the core streaming parser and generator.
 *
 * <p>Decoding rejects envelopes that violate the specification (wrong jsonrpc version,
 * request carrying a response member, id that is not a number or a string). It
 * accepts the spec shaped corner case of an error response with a null id, which a
 * peer answers when it cannot even recover the id. For request shaped invalid
 * envelopes whose id could be recovered, the raised
 * {@link JsonRpcProtocolException} carries the id so the receiver can answer with
 * {@code INVALID_REQUEST}.</p>
 */
public final class JsonRpcMessageCodec {

    public static final String JSONRPC_VERSION = "2.0";
    public static final String CANCEL_REQUEST_METHOD = "$/cancelRequest";

    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_ID = "id";
    private static final String KEY_METHOD = "method";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_RESULT = "result";
    private static final String KEY_ERROR = "error";

    private JsonRpcMessageCodec() {
    }

    public static String encode(JsonRpcMessage message) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put(KEY_JSONRPC, JSONRPC_VERSION);
        switch (message) {
            case JsonRpcRequest request -> encodeRequest(tree, request);
            case JsonRpcNotification notification -> encodeNotification(tree, notification);
            case JsonRpcResponse response -> encodeResponse(tree, response);
        }
        return Json.write(tree);
    }

    private static void encodeRequest(Map<String, Object> tree, JsonRpcRequest request) {
        tree.put(KEY_METHOD, request.method());
        tree.put(KEY_ID, request.id().asValue());
        if (request.params() != null) {
            tree.put(KEY_PARAMS, encodeParams(request.params()));
        }
    }

    private static void encodeNotification(Map<String, Object> tree, JsonRpcNotification notification) {
        tree.put(KEY_METHOD, notification.method());
        if (notification.params() != null) {
            tree.put(KEY_PARAMS, encodeParams(notification.params()));
        }
    }

    private static void encodeResponse(Map<String, Object> tree, JsonRpcResponse response) {
        if (response.id() == null) {
            throw new IllegalArgumentException(
                    "only a received response may carry a null id, an outbound one cannot");
        }
        tree.put(KEY_ID, response.id().asValue());
        if (response.error() != null) {
            tree.put(KEY_ERROR, response.error().asTree());
        } else {
            tree.put(KEY_RESULT, response.result());
        }
    }

    private static Object encodeParams(Object params) {
        if (!(params instanceof Map<?, ?>) && !(params instanceof List<?>)) {
            throw new IllegalArgumentException("params must be a JSON object or array");
        }
        return params;
    }

    public static JsonRpcMessage decode(String text) {
        Object tree = Json.parse(text);
        if (!(tree instanceof Map<?, ?> fields)) {
            throw new JsonRpcProtocolException("message must be a JSON object");
        }
        if (!JSONRPC_VERSION.equals(fields.get(KEY_JSONRPC))) {
            throw new JsonRpcProtocolException("jsonrpc must be \"2.0\"");
        }
        if (fields.containsKey(KEY_METHOD)) {
            return decodeWithMethod(fields, fields.get(KEY_METHOD));
        }
        return decodeResponseWithEnvelope(fields);
    }

    private static JsonRpcMessage decodeWithMethod(Map<?, ?> fields, Object methodValue) {
        JsonRpcId recoverableId = recoverRequestId(fields);
        if (!(methodValue instanceof String method) || method.isBlank()) {
            throw new JsonRpcProtocolException("method must be a non blank string", recoverableId);
        }
        if (fields.containsKey(KEY_ID)) {
            if (fields.get(KEY_ID) == null) {
                throw new JsonRpcProtocolException("request id must not be null");
            }
            if (fields.containsKey(KEY_ERROR) || fields.containsKey(KEY_RESULT)) {
                throw new JsonRpcProtocolException(
                        "request must not carry result or error", recoverableId);
            }
            return new JsonRpcRequest(JsonRpcId.fromTree(fields.get(KEY_ID)), method,
                    decodeParams(fields));
        }
        if (fields.containsKey(KEY_ERROR) || fields.containsKey(KEY_RESULT)) {
            throw new JsonRpcProtocolException("notification must not carry result or error");
        }
        return new JsonRpcNotification(method, decodeParams(fields));
    }

    private static JsonRpcMessage decodeResponseWithEnvelope(Map<?, ?> fields) {
        JsonRpcError error = fields.containsKey(KEY_ERROR) ? extractError(fields) : null;
        if (!fields.containsKey(KEY_ID) || fields.get(KEY_ID) == null) {
            if (error == null || fields.containsKey(KEY_RESULT)) {
                throw new JsonRpcProtocolException(
                        "response must carry an id, or a non null error without id");
            }
            return new JsonRpcResponse(null, null, error);
        }
        JsonRpcId id = JsonRpcId.fromTree(fields.get(KEY_ID));
        if (error != null) {
            if (fields.containsKey(KEY_RESULT)) {
                throw new JsonRpcProtocolException("error response must not carry result", id);
            }
            return new JsonRpcResponse(id, null, error);
        }
        if (!fields.containsKey(KEY_RESULT)) {
            throw new JsonRpcProtocolException("response must carry result or error", id);
        }
        return new JsonRpcResponse(id, fields.get(KEY_RESULT), null);
    }

    private static JsonRpcError extractError(Map<?, ?> fields) {
        Object errorValue = fields.get(KEY_ERROR);
        if (!(errorValue instanceof Map<?, ?>)) {
            throw new JsonRpcProtocolException("error must be a JSON object");
        }
        return JsonRpcError.fromTree(errorValue);
    }

    private static JsonRpcId recoverRequestId(Map<?, ?> fields) {
        Object rawId = fields.get(KEY_ID);
        if (rawId == null) {
            return null;
        }
        try {
            return JsonRpcId.fromTree(rawId);
        } catch (JsonRpcProtocolException malformedId) {
            return null;
        }
    }

    private static Object decodeParams(Map<?, ?> fields) throws JsonRpcProtocolException {
        Object params = fields.get(KEY_PARAMS);
        if (params == null) {
            return null;
        }
        if (!(params instanceof Map<?, ?>) && !(params instanceof List<?>)) {
            throw new JsonRpcProtocolException(
                    "params must be a JSON object or array", recoverRequestId(fields));
        }
        return params;
    }

    public static boolean isCancellation(JsonRpcNotification notification) {
        return CANCEL_REQUEST_METHOD.equals(notification.method());
    }

    /**
     * Extracts the id a cancellation notification targets through the LSP style
     * {@code id} member inside the params object.
     */
    public static JsonRpcId cancellationTarget(JsonRpcNotification notification) {
        if (!isCancellation(notification)) {
            return null;
        }
        if (notification.params() instanceof Map<?, ?> params) {
            Object target = params.get(KEY_ID);
            return target == null ? null : JsonRpcId.fromTree(target);
        }
        return null;
    }

    /**
     * Builds the {@code $/cancelRequest} notification for a request id, with the id
     * inside the params object.
     */
    public static JsonRpcNotification cancellationNotification(JsonRpcId targetId) {
        Objects.requireNonNull(targetId, "target id");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(KEY_ID, targetId.asValue());
        return new JsonRpcNotification(CANCEL_REQUEST_METHOD, params);
    }
}
