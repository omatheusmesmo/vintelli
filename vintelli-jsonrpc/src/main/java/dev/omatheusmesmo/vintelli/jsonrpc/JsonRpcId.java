package dev.omatheusmesmo.vintelli.jsonrpc;

import java.math.BigInteger;
import java.util.Objects;

/**
 * JSON-RPC 2.0 request identifier. Per the specification a request id is a string or
 * a number; null ids are not accepted and magnitudes beyond {@code long} precision
 * are rejected rather than silently degraded to a double.
 */
public record JsonRpcId(Long numeric, String text) {

    public JsonRpcId {
        if ((numeric == null) == (text == null)) {
            throw new IllegalArgumentException("id must be exactly one of numeric or text");
        }
    }

    public static JsonRpcId of(long value) {
        return new JsonRpcId(Long.valueOf(value), null);
    }

    public static JsonRpcId of(String value) {
        return new JsonRpcId(null, Objects.requireNonNull(value, "id text"));
    }

    public static JsonRpcId fromTree(Object value) {
        return switch (value) {
            case Long number -> new JsonRpcId(number, null);
            case Integer number -> new JsonRpcId(number.longValue(), null);
            case Double number -> number == Math.floor(number)
                    ? new JsonRpcId(number.longValue(), null)
                    : throwInvalid(value);
            case BigInteger ignored -> throwInvalid(value);
            case String text -> new JsonRpcId(null, text);
            default -> throwInvalid(value);
        };
    }

    private static JsonRpcId throwInvalid(Object value) {
        throw new JsonRpcProtocolException(
                "id must be a string or a number within long precision, got " + value);
    }

    public Object asValue() {
        return numeric != null ? numeric : (Object) text;
    }

    @Override
    public String toString() {
        return numeric != null ? Long.toString(numeric.longValue()) : "\"" + text + "\"";
    }
}
