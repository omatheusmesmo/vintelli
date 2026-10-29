package dev.omatheusmesmo.vintelli.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class JsonRpcConnectionTest {

    @Test
    void sendThenReceiveOverTheSameBytesForBothFramings() throws IOException {
        roundTripOn(ContentLengthFraming.STANDARD);
        roundTripOn(LineDelimitedFraming.STANDARD);
    }

    @Test
    void nonAsciiPayloadsRoundTripForBothFramings() throws IOException {
        JsonRpcMessage message = new JsonRpcNotification("session/update",
                TestSupport.params("text", "caf\u00e9 \u4f60\u597d \ud83d\ude00"));
        roundTripOn(message, ContentLengthFraming.STANDARD);
        roundTripOn(message, LineDelimitedFraming.STANDARD);
    }

    private static void roundTripOn(JsonRpcFraming framing) throws IOException {
        roundTripOn(new JsonRpcNotification("initialized", null), framing);
    }

    private static void roundTripOn(JsonRpcMessage message, JsonRpcFraming framing) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        JsonRpcConnection sender = new JsonRpcConnection(framing,
                new ByteArrayInputStream(new byte[0]), raw);
        sender.send(message);
        JsonRpcConnection reader = new JsonRpcConnection(framing,
                new ByteArrayInputStream(raw.toByteArray()), new ByteArrayOutputStream());
        assertEquals(message, reader.receive());
    }

    @Test
    void payloadDecodeErrorsDoNotDamageTheFrameStream() throws IOException {
        byte[] truncatedBody = "{\"jsonrpc\":\"2.0\",\"method\":\"x\",".getBytes(StandardCharsets.UTF_8);
        byte[] goodPayload = "{\"jsonrpc\":\"2.0\",\"method\":\"m\"}".getBytes(StandardCharsets.UTF_8);
        ContentLengthFraming framing = new ContentLengthFraming(512);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        framing.writeFrame(raw, truncatedBody);
        framing.writeFrame(raw, goodPayload);
        JsonRpcConnection reader = new JsonRpcConnection(framing,
                new ByteArrayInputStream(raw.toByteArray()), new ByteArrayOutputStream());
        assertThrows(JsonRpcProtocolException.class, reader::receive);
        assertEquals(new JsonRpcNotification("m", null), reader.receive());
    }

    @Test
    void receiveStopsAtCleanEndOfStream() throws IOException {
        byte[] goodPayload = "{\"jsonrpc\":\"2.0\",\"method\":\"m\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        LineDelimitedFraming.STANDARD.writeFrame(raw, goodPayload);
        JsonRpcConnection reader = new JsonRpcConnection(LineDelimitedFraming.STANDARD,
                new ByteArrayInputStream(raw.toByteArray()), new ByteArrayOutputStream());
        assertEquals(new JsonRpcNotification("m", null), reader.receive());
        assertNull(reader.receive());
    }

    @Test
    void aFailedSendBreaksTheConnectionAndLaterSendsFailCleanly() throws IOException {
        FailingAfterFirstWrite failing = new FailingAfterFirstWrite();
        JsonRpcConnection connection = new JsonRpcConnection(
                ContentLengthFraming.STANDARD,
                new ByteArrayInputStream(new byte[0]),
                failing);
        connection.send(new JsonRpcNotification("first", null));
        assertThrows(IOException.class,
                () -> connection.send(new JsonRpcNotification("second", null)));
        assertTrue(failing.brokeAtSecondWrite(), "the failed partial write must mark the connection");
        assertThrows(IOException.class,
                () -> connection.send(new JsonRpcNotification("third", null)));
    }

    private static final class FailingAfterFirstWrite extends OutputStream {

        private int writes;
        private boolean broke;

        boolean brokeAtSecondWrite() {
            return broke;
        }

        @Override
        public void write(int value) throws IOException {
            writes++;
            if (writes > 1) {
                broke = true;
                throw new IOException("pipe vanished mid frame");
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            writes++;
            if (writes > 1) {
                broke = true;
                throw new IOException("pipe vanished mid frame");
            }
        }
    }
}
