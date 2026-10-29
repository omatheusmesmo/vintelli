package dev.omatheusmesmo.vintelli.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonRpcMessageCodecTest {

    @Test
    void encodesRequestExactly() {
        JsonRpcRequest request = new JsonRpcRequest(
                JsonRpcId.of(7), "echo", TestSupport.params("a", Boolean.TRUE));
        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"id\":7,\"params\":{\"a\":true}}",
                JsonRpcMessageCodec.encode(request));
    }

    @Test
    void encodesNotificationAndOmitsNullParams() {
        JsonRpcNotification notification = new JsonRpcNotification("initialized", null);
        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}",
                JsonRpcMessageCodec.encode(notification));
    }

    @Test
    void encodesStringId() {
        JsonRpcRequest request = new JsonRpcRequest(JsonRpcId.of("abc"), "m", null);
        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":\"abc\"}",
                JsonRpcMessageCodec.encode(request));
    }

    @Test
    void encodesSuccessWithNullResult() {
        JsonRpcResponse response = JsonRpcResponse.success(JsonRpcId.of(2), null);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":null}",
                JsonRpcMessageCodec.encode(response));
    }

    @Test
    void encodesErrorResponseWithoutResult() {
        JsonRpcError error = JsonRpcError.of(JsonRpcError.INVALID_REQUEST, "bad envelope");
        JsonRpcResponse response = JsonRpcResponse.failure(JsonRpcId.of(9), error);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32600,\"message\":\"bad envelope\"}}",
                JsonRpcMessageCodec.encode(response));
    }

    @Test
    void roundTripsAllMessageTypes() {
        JsonRpcRequest request = new JsonRpcRequest(
                JsonRpcId.of("r-1"), "initialize", TestSupport.params("deep", TestSupport.listOf(Long.valueOf(1))));
        assertEquals(request, JsonRpcMessageCodec.decode(JsonRpcMessageCodec.encode(request)));
        JsonRpcNotification notification = new JsonRpcNotification("exit", TestSupport.params("code", Long.valueOf(0)));
        assertEquals(notification, JsonRpcMessageCodec.decode(JsonRpcMessageCodec.encode(notification)));
        JsonRpcResponse success = JsonRpcResponse.success(JsonRpcId.of(3), TestSupport.listOf("a"));
        assertEquals(success, JsonRpcMessageCodec.decode(JsonRpcMessageCodec.encode(success)));
        JsonRpcResponse failure = JsonRpcResponse.failure(JsonRpcId.of("r-2"),
                new JsonRpcError(JsonRpcError.METHOD_NOT_FOUND, "missing", null));
        assertEquals(failure, JsonRpcMessageCodec.decode(JsonRpcMessageCodec.encode(failure)));
    }

    @Test
    void keepsErrorDataMember() {
        JsonRpcError error = new JsonRpcError(JsonRpcError.INVALID_PARAMS, "bad input",
                TestSupport.params("field", "owner"));
        JsonRpcResponse response = JsonRpcResponse.failure(JsonRpcId.of(5), error);
        JsonRpcResponse back = assertInstanceOf(JsonRpcResponse.class,
                JsonRpcMessageCodec.decode(JsonRpcMessageCodec.encode(response)));
        assertEquals("bad input", back.error().message());
        Map<String, Object> data = asMap(back.error().data());
        assertEquals("owner", data.get("field"));
    }

    @Test
    void rejectsBrokenEnvelopes() {
        assertThrows(JsonRpcProtocolException.class, () -> JsonRpcMessageCodec.decode("[{\"jsonrpc\":\"2.0\"}]"));
        assertThrows(JsonRpcProtocolException.class, () -> JsonRpcMessageCodec.decode("{}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"1.0\",\"method\":\"m\",\"id\":1}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":null}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":1.5}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\",\"method\":\"\",\"id\":1}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":1,\"result\":{}}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\"}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\",\"id\":1}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"params\":\"scalar\"}"));
        assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"no code\"}}"));
    }

    @Test
    void cancellationHelpersFollowTheLspStyle() {
        JsonRpcNotification cancellation =
                JsonRpcMessageCodec.cancellationNotification(JsonRpcId.of(12));
        assertEquals(
                "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":12}}",
                JsonRpcMessageCodec.encode(cancellation));
        assertTrue(JsonRpcMessageCodec.isCancellation(cancellation));
        assertEquals(Long.valueOf(12),
                JsonRpcMessageCodec.cancellationTarget(cancellation).numeric());

        JsonRpcNotification cancellationWithTextId = assertInstanceOf(
                JsonRpcNotification.class, JsonRpcMessageCodec.decode(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":\"job-3\"}}"));
        assertEquals(JsonRpcId.of("job-3"),
                JsonRpcMessageCodec.cancellationTarget(cancellationWithTextId));

        JsonRpcNotification other = new JsonRpcNotification("session/prompt", null);
        assertNull(JsonRpcMessageCodec.cancellationTarget(other));
    }

    @Test
    void mcpStyleRequestIdMembersAreNotPartOfTheSharedProtocol() {
        JsonRpcNotification mcpStyle = new JsonRpcNotification(
                JsonRpcMessageCodec.CANCEL_REQUEST_METHOD, TestSupport.params("requestId", "job-3"));
        assertNull(JsonRpcMessageCodec.cancellationTarget(mcpStyle));
    }

    @Test
    void decodesAnErrorResponseWithNullId() {
        JsonRpcMessage decoded = JsonRpcMessageCodec.decode(
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"broken\"}}");
        JsonRpcResponse response = assertInstanceOf(JsonRpcResponse.class, decoded);
        assertNull(response.id());
        assertEquals(JsonRpcError.PARSE_ERROR, response.error().code());
    }

    @Test
    void rejectsResponsesWithoutAnyId() {
        assertThrows(JsonRpcProtocolException.class, () -> JsonRpcMessageCodec.decode(
                "{\"jsonrpc\":\"2.0\",\"result\":{}}"));
        assertThrows(JsonRpcProtocolException.class, () -> JsonRpcMessageCodec.decode(
                "{\"jsonrpc\":\"2.0\",\"result\":{},\"error\":{\"code\":-32700,\"message\":\"broken\"}}"));
    }

    @Test
    void requestShapedInvalidMessagesCarryTheRecoverableId() {
        JsonRpcProtocolException blankMethod = assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode("{\"jsonrpc\":\"2.0\",\"method\":\"\",\"id\":51}"));
        assertEquals(JsonRpcId.of(51), blankMethod.recoverableId());

        JsonRpcProtocolException badParams = assertThrows(JsonRpcProtocolException.class,
                () -> JsonRpcMessageCodec.decode(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"params\":\"scalar\",\"id\":7}"));
        assertEquals(JsonRpcId.of(7), badParams.recoverableId());
    }

    @Test
    void idsBeyondLongPrecisionAreRejected() {
        assertThrows(JsonRpcProtocolException.class, () -> JsonRpcMessageCodec.decode(
                "{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"id\":90071992547409930000}"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
