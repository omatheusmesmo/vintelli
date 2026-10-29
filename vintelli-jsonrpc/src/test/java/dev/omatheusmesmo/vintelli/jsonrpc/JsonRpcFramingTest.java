package dev.omatheusmesmo.vintelli.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

class JsonRpcFramingTest {

    @Test
    void contentLengthFramingRoundTripsWithExtraHeaders() throws IOException {
        ContentLengthFraming framing = new ContentLengthFraming(1024);
        byte[] payload = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        framing.writeFrame(out, payload);
        String expected = "Content-Length: 7\r\n\r\n{\"x\":1}";
        assertEquals(expected, out.toString(StandardCharsets.US_ASCII));

        ByteArrayInputStream in = new ByteArrayInputStream(
                "Charsets: utf-8\r\ncontent-length: 7\r\n\r\n{\"x\":1}".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(payload, framing.readFrame(in));
        assertNull(framing.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void contentLengthFramingReadsExactBodyNotMore() throws IOException {
        ContentLengthFraming framing = new ContentLengthFraming(1024);
        ByteArrayInputStream in = new ByteArrayInputStream(
                "Content-Length: 4\r\n\r\nabcdBodyTrailer".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals("abcd".getBytes(StandardCharsets.US_ASCII), framing.readFrame(in));
        byte[] rest = in.readAllBytes();
        assertEquals("BodyTrailer", new String(rest, StandardCharsets.US_ASCII));
    }

    @Test
    void contentLengthHeaderNameMustMatchExactly() throws IOException {
        ContentLengthFraming framing = new ContentLengthFraming(1024);
        assertArrayEquals("abcd".getBytes(StandardCharsets.US_ASCII), framing.readFrame(stream(
                "X-Content-Length: 9\r\nContent-Length-Extra: 999\r\nContent-Length: 4\r\n\r\nabcd")));
    }

    @Test
    void contentLengthDuplicateHeadersAreRejected() {
        ContentLengthFraming framing = new ContentLengthFraming(1024);
        assertThrows(IOException.class, () -> framing.readFrame(stream(
                "Content-Length: 4\r\ncontent-length: 7\r\n\r\nabcdstuff")));
        assertThrows(IOException.class, () -> framing.readFrame(stream(
                "Content-Length: 4\r\nContent-Length: 4\r\n\r\nabcd")));
    }

    @Test
    void contentLengthFramingRejectsBadHeadersWithoutUnboundedBuffers() {
        ContentLengthFraming framing = new ContentLengthFraming(16);
        assertThrows(IOException.class, () -> framing.readFrame(stream("Content-Length: abc\r\n\r\n1234567890")));
        assertThrows(IOException.class, () -> framing.readFrame(stream("Content-Length: 32\r\n\r\n12345678901234567890123456789012")));
        assertThrows(IOException.class, () -> framing.readFrame(stream("Content-Length: 96501638400\r\n\r\n")));
        assertThrows(IOException.class, () -> framing.readFrame(stream("\r\n\r\n")));
        assertThrows(IOException.class, () -> framing.readFrame(stream("Content-Length 4\r\n\r\nabcd")));
        assertThrows(IOException.class, () -> framing.readFrame(stream("Content-Length: 4")));
        assertThrows(IOException.class, () -> framing.readFrame(stream(
                repeatedHeaderLines("Content-Length: 4\r\n"))));
        assertThrows(IOException.class, () -> framing.readFrame(stream(
                repeatedHeaderLines("X-Header-Content-Length: 4\r\n"))));
    }

    @Test
    void oversizedHeaderLineFailsWithoutUnboundedAllocations() {
        ContentLengthFraming framing = new ContentLengthFraming(1024);
        String overLongHeader = "X-Notes: " + "A".repeat(FrameReader.MAX_HEADER_LINE_BYTES + 64)
                + "\r\nContent-Length: 4\r\n\r\nabcd";
        assertThrows(IOException.class, () -> framing.readFrame(stream(overLongHeader)));
    }

    @Test
    void truncatedBodyFailsAsMidFrameFault() {
        ContentLengthFraming framing = new ContentLengthFraming(1024);
        byte[] halfBody = "12345".getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = concat("Content-Length: 10\r\n\r\n".getBytes(StandardCharsets.US_ASCII), halfBody);
        assertThrows(IOException.class, () -> framing.readFrame(new ByteArrayInputStream(bytes)));
    }

    @Test
    void nonAsciiPayloadBytesSurviveBothFramings() throws IOException {
        byte[] payload = NonAscii.PAYLOAD_BYTES;
        assertArrayEquals(payload, roundTrip(payload, ContentLengthFraming.STANDARD));
        assertArrayEquals(payload, roundTrip(payload, LineDelimitedFraming.STANDARD));
    }

    @Test
    void nonAsciiPayloadByteLengthDrivesTheContentLengthHeader() throws IOException {
        ContentLengthFraming framing = new ContentLengthFraming(1024);
        byte[] payload = NonAscii.PAYLOAD_BYTES;
        assertTrue(payload.length != new String(payload, StandardCharsets.UTF_8).length(),
                "the header has to count bytes, not chars");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        framing.writeFrame(out, payload);
        assertEquals("Content-Length: " + payload.length,
                out.toString(StandardCharsets.US_ASCII).split("\r\n", 2)[0]);
    }

    @Test
    void lineDelimitedFramingRoundTripsSingleNewlineMessages() throws IOException {
        LineDelimitedFraming framing = new LineDelimitedFraming(1024);
        byte[] payload = "{\"jsonrpc\":\"2.0\",\"method\":\"m\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        framing.writeFrame(out, payload);
        assertEquals(payload.length + 1, out.size());

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertArrayEquals(payload, framing.readFrame(in));

        ByteArrayInputStream crlf = new ByteArrayInputStream(
                "{\"method\":\"m\"}\r\n".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals("{\"method\":\"m\"}".getBytes(StandardCharsets.US_ASCII), framing.readFrame(crlf));
        assertNull(framing.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void lineDelimitedFramingSkipsBlankLines() throws IOException {
        LineDelimitedFraming framing = new LineDelimitedFraming(64);
        byte[] message = "{\"method\":\"m\"}".getBytes(StandardCharsets.UTF_8);
        byte[] blank = "\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = concat(blank, message);
        bytes = concat(bytes, new byte[] {'\n'});
        assertArrayEquals(message, framing.readFrame(new ByteArrayInputStream(bytes)));

        ByteArrayInputStream trailingBlanksOnly = new ByteArrayInputStream("\n\r\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> framing.readFrame(trailingBlanksOnly));
    }

    @Test
    void lineDelimitedFramingRejectsPayloadWithEmbeddedNewline() {
        LineDelimitedFraming framing = new LineDelimitedFraming(64);
        byte[] payload = "{\"a\":1\n,\"b\":2}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> framing.writeFrame(OutputStream.nullOutputStream(), payload));
    }

    @Test
    void lineDelimitedFramingRejectsOversizedLines() {
        LineDelimitedFraming framing = new LineDelimitedFraming(8);
        assertThrows(IOException.class, () -> framing.readFrame(stream("123456789012345\n")));
        assertThrows(IOException.class, () -> framing.readFrame(stream("123456789\n")));
    }

    @Test
    void lineDelimitedFramingAllowsExactMaxLine() throws IOException {
        LineDelimitedFraming framing = new LineDelimitedFraming(8);
        ByteArrayInputStream in = new ByteArrayInputStream("12345678\n".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals("12345678".getBytes(StandardCharsets.US_ASCII), framing.readFrame(in));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String repeatedHeaderLines(String line) {
        StringBuilder headerBlock = new StringBuilder();
        for (int index = 0; index <= FrameReader.MAX_HEADER_LINES + 2; index++) {
            headerBlock.append(line);
        }
        return headerBlock.toString();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static byte[] roundTrip(byte[] payload, JsonRpcFraming framing) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        framing.writeFrame(out, payload);
        return framing.readFrame(new ByteArrayInputStream(out.toByteArray()));
    }

    private static final class NonAscii {

        static final byte[] PAYLOAD_BYTES = ("caf\u00e9 \u4f60\u597d \ud83d\ude00").getBytes(StandardCharsets.UTF_8);
    }
}
