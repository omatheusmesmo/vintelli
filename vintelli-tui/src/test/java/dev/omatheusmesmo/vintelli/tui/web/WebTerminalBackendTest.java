package dev.omatheusmesmo.vintelli.tui.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of the wire encoding the browser terminal session uses:
 * control characters of shell content never reach the client stream, sizes
 * stay inside the clamped buffer and the input queue behaves like the reader
 * the TamboUI event loop expects (peek then read, negative sentinel on
 * timeouts, bounded when flooded).
 */
class WebTerminalBackendTest {

    private static final Rect AREA = Rect.of(8, 2);

    @Test
    void controlCharactersInSymbolsNeverReachTheStream() throws Exception {
        Buffer after = Buffer.empty(AREA);
        after.set(0, 1, new Cell("e", Style.create()));
        after.set(1, 1, new Cell("\u001b", Style.create()));
        after.set(2, 1, new Cell("\u0007", Style.create()));
        DiffResult diff = diff(Buffer.empty(AREA), after);
        List<String> chunks = new ArrayList<>();
        new WebTerminalBackend(chunks::add).draw(diff);

        String stream = String.join("", chunks);
        assertTrue(stream.contains("e??"), () -> stream);
        assertFalse(stream.contains("\u0007"), () -> stream);
    }

    @Test
    void plainSymbolsPassThroughUnchanged() throws Exception {
        DiffResult diff = diff(before("first"), after("plain"));
        List<String> chunks = new ArrayList<>();
        new WebTerminalBackend(chunks::add).draw(diff);

        assertTrue(String.join("", chunks).contains("plain"));
    }

    @Test
    void sanitizeLeavesNormalTextAndAnswersControlCarriers() throws Exception {
        assertEquals("abc", WebTerminalBackend.sanitize("abc"));
        assertEquals("?", WebTerminalBackend.sanitize("\u001b"));
        assertEquals("?m", WebTerminalBackend.sanitize("\u001bm"));
        assertEquals("?x?", WebTerminalBackend.sanitize("\u007fx\u000B"));
        assertEquals("?x?", WebTerminalBackend.sanitize("\u009fx\u0085"));
    }

    @Test
    void sizesStayInsideTheClampedBuffer() throws Exception {
        WebTerminalBackend backend = backendSink();
        backend.updateSize(100_000, 100_000);
        assertEquals(400, backend.currentSize().width());
        assertEquals(200, backend.currentSize().height());

        backend.updateSize(0, 0);
        assertEquals(20, backend.currentSize().width());
        assertEquals(5, backend.currentSize().height());
    }

    @Test
    void inputQueuePeeksThenReadsAndReportsIdleTime() throws Exception {
        WebTerminalBackend backend = backendSink();
        assertEquals(-2, backend.peek(1));
        assertEquals(-2, backend.read(1));

        assertTrue(backend.acceptInput(new byte[] {0x41, 0x42}));
        assertEquals(0x41, backend.peek(1));
        assertEquals(0x41, backend.read(0));
        assertEquals(0x42, backend.read(0));
        assertEquals(-2, backend.read(1));
    }

    @Test
    void inputQueueDropsSurplusWhenTheClientFloods() throws Exception {
        WebTerminalBackend backend = backendSink();
        byte[] flood = new byte[4096 + 128];
        Arrays.fill(flood, (byte) 'x');
        assertFalse(backend.acceptInput(flood));

        int drained = 0;
        while (backend.read(0) > 0) {
            drained++;
        }
        assertEquals(4096, drained);
    }

    @Test
    void closeWritesTheRestoreSequencesOnceAndDropsEveryLaterWrite() throws Exception {
        List<String> chunks = new ArrayList<>();
        WebTerminalBackend backend = new WebTerminalBackend(chunks::add);
        assertTrue(backend.acceptInput("x"));

        backend.close();

        assertEquals(List.of("\u001b[?25h\u001b[?1049l"), chunks);
        assertTrue(backend.closed());
        assertEquals(-2, backend.read(0));

        backend.showCursor();
        backend.writeRaw("late");
        backend.flush();
        assertEquals(1, chunks.size(), () -> String.join("", chunks));
        assertFalse(backend.acceptInput("y"));
    }

    private static WebTerminalBackend backendSink() {
        return new WebTerminalBackend(ignored -> {
        });
    }

    private static Buffer before(String text) {
        Buffer buffer = Buffer.empty(AREA);
        set(buffer, text, 0);
        return buffer;
    }

    private static Buffer after(String text) {
        Buffer buffer = Buffer.empty(AREA);
        set(buffer, text, 1);
        return buffer;
    }

    private static void set(Buffer buffer, String text, int row) {
        buffer.setString(0, row, text, Style.create());
    }

    private static DiffResult diff(Buffer before, Buffer after) {
        DiffResult result = new DiffResult();
        before.diff(after, result);
        assertFalse(result.isEmpty());
        return result;
    }
}
