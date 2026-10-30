package dev.omatheusmesmo.vintelli.tui.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import dev.tamboui.buffer.Cell;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.AnsiCellWriter;
import dev.tamboui.terminal.Backend;
import org.jboss.logging.Logger;

/**
 * The TamboUI half of one browser terminal session: a {@link Backend} that
 * streams the rendered screen to the client as ANSI bytes and accepts the
 * client's key bytes for the shell event loop to decode. A per-session TamboUI
 * runner owns one instance, so every WebSocket connection gets its own screen
 * buffer, size and render diff.
 *
 * <p>Two invariants keep the surface safe. Outgoing cell symbols pass through
 * {@link #sanitize(String)}, so shell content that carries terminal control
 * characters can never drive the browser terminal; only the sequences the
 * encoder itself emits (style and cursor codes) reach the wire as controls.
 * Incoming bytes are queued for parsing and never echoed, and window sizes
 * handed over from the WebSocket layer are clamped, so no resize message can
 * ask for an absurd buffer.
 */
final class WebTerminalBackend implements Backend {

    private static final Logger LOG = Logger.getLogger(WebTerminalBackend.class);
    static final int DEFAULT_COLUMNS = 80;
    static final int DEFAULT_ROWS = 24;
    static final int MIN_COLUMNS = 20;
    static final int MAX_COLUMNS = 400;
    static final int MIN_ROWS = 5;
    static final int MAX_ROWS = 200;

    private static final int INPUT_CAPACITY = 4096;
    private static final int NO_DATA = -2;

    private static final String ALTERNATE_SCREEN_ENTER = "\u001b[?1049h\u001b[H\u001b[2J";
    private static final String ALTERNATE_SCREEN_LEAVE = "\u001b[?1049l";
    private static final String SCREEN_CLEAR = "\u001b[H\u001b[2J";
    private static final String CURSOR_SHOW = "\u001b[?25h";
    private static final String CURSOR_HIDE = "\u001b[?25l";
    private static final String MOUSE_OFF = "\u001b[?1002l\u001b[?1003l";
    private static final String BRACKETED_PASTE_ON = "\u001b[?2004h";
    private static final String BRACKETED_PASTE_OFF = "\u001b[?2004l";
    private static final String SYNC_BEGIN = "\u001b[?2026h";
    private static final String SYNC_END = "\u001b[?2026l";
    private static final char DEL = 0x7f;

    private final WebTerminalOutput output;
    private final ArrayBlockingQueue<Integer> input = new ArrayBlockingQueue<>(INPUT_CAPACITY);
    private final StringBuilder pending = new StringBuilder();

    private volatile Size size = new Size(DEFAULT_COLUMNS, DEFAULT_ROWS);
    private volatile boolean closed;
    private Integer peeked;

    WebTerminalBackend(WebTerminalOutput output) {
        this.output = output;
    }

    /**
     * Queues client bytes as code points for the shell event loop to decode
     * into key events. The queue is bounded: when a client floods it, surplus
     * bytes are dropped instead of buffering without a limit. Returns whether
     * every byte could be queued.
     */
    boolean acceptInput(byte[] bytes) {
        return acceptInput(new String(bytes, StandardCharsets.UTF_8));
    }

    boolean acceptInput(String bytes) {
        boolean complete = true;
        for (int offset = 0; offset < bytes.length(); offset++) {
            char character = bytes.charAt(offset);
            char low = bytes.length() > offset + 1 ? bytes.charAt(offset + 1) : 0;
            if (Character.isHighSurrogate(character) && Character.isLowSurrogate(low)) {
                complete &= offer(Character.toCodePoint(character, low));
                offset++;
            } else {
                complete &= offer(character);
            }
        }
        return complete;
    }

    private boolean offer(int codePoint) {
        if (closed) {
            return false;
        }
        return input.offer(codePoint);
    }

    void updateSize(int columns, int rows) {
        size = new Size(clamp(columns, MIN_COLUMNS, MAX_COLUMNS), clamp(rows, MIN_ROWS, MAX_ROWS));
    }

    Size currentSize() {
        return size;
    }

    boolean closed() {
        return closed;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Replaces terminal control characters with a question mark so cell
     * content can never carry escape sequences or other controls into the
     * browser terminal. Plain text passes through unchanged.
     */
    static String sanitize(String symbol) {
        boolean dirty = false;
        int length = symbol.length();
        for (int index = 0; index < length; index++) {
            if (isControl(symbol.charAt(index))) {
                dirty = true;
                break;
            }
        }
        if (!dirty) {
            return symbol;
        }
        StringBuilder out = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            out.append(isControl(symbol.charAt(index)) ? '?' : symbol.charAt(index));
        }
        return out.toString();
    }

    private static boolean isControl(char character) {
        return Character.isISOControl(character) || character == DEL || (character >= 0x80 && character <= 0x9f);
    }

    @Override
    public void draw(DiffResult diff) throws IOException {
        if (diff.isEmpty()) {
            return;
        }
        try (AnsiCellWriter writer = new AnsiCellWriter(pending::append)) {
            for (int run = 0; run < diff.runCount(); run++) {
                int start = diff.runStart(run);
                int end = start + diff.runLength(run);
                while (start < end && diff.cellAt(start).isContinuation()) {
                    start++;
                }
                if (start < end) {
                    writeRaw("\u001b[" + (diff.yOf(start) + 1) + ";" + (diff.xOf(start) + 1) + "H");
                    for (int cell = start; cell < end; cell++) {
                        Cell source = diff.cellAt(cell);
                        String clean = sanitize(source.symbol());
                        writer.writeCell(clean.equals(source.symbol())
                                ? source
                                : new Cell(clean, source.style()));
                    }
                }
            }
        }
        flush();
    }

    @Override
    public void flush() throws IOException {
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            String chunk = pending.toString();
            pending.setLength(0);
            output.write(chunk);
        }
    }

    @Override
    public void clear() throws IOException {
        writeRaw(SCREEN_CLEAR);
        flush();
    }

    @Override
    public Size size() {
        return size;
    }

    @Override
    public void showCursor() throws IOException {
        writeRaw(CURSOR_SHOW);
        flush();
    }

    @Override
    public void hideCursor() throws IOException {
        writeRaw(CURSOR_HIDE);
        flush();
    }

    @Override
    public Position getCursorPosition() {
        return Position.ORIGIN;
    }

    @Override
    public void setCursorPosition(Position position) throws IOException {
        writeRaw("\u001b[" + (position.y() + 1) + ";" + (position.x() + 1) + "H");
        flush();
    }

    @Override
    public void enterAlternateScreen() throws IOException {
        writeRaw(ALTERNATE_SCREEN_ENTER);
        flush();
    }

    @Override
    public void leaveAlternateScreen() throws IOException {
        writeRaw(ALTERNATE_SCREEN_LEAVE);
        flush();
    }

    @Override
    public void enableRawMode() throws IOException {
    }

    @Override
    public void disableRawMode() throws IOException {
    }

    @Override
    public void enableBracketedPaste() throws IOException {
        writeRaw(BRACKETED_PASTE_ON);
        flush();
    }

    @Override
    public void disableBracketedPaste() throws IOException {
        writeRaw(BRACKETED_PASTE_OFF);
        flush();
    }

    @Override
    public void beginSynchronizedUpdate() throws IOException {
        writeRaw(SYNC_BEGIN);
        flush();
    }

    @Override
    public void endSynchronizedUpdate() throws IOException {
        writeRaw(SYNC_END);
        flush();
    }

    @Override
    public void scrollUp(int lines) throws IOException {
        writeRaw("\u001b[" + lines + "S");
        flush();
    }

    @Override
    public void scrollDown(int lines) throws IOException {
        writeRaw("\u001b[" + lines + "T");
        flush();
    }

    @Override
    public void insertLines(int lines) throws IOException {
        writeRaw("\u001b[" + lines + "L");
        flush();
    }

    @Override
    public void deleteLines(int lines) throws IOException {
        writeRaw("\u001b[" + lines + "M");
        flush();
    }

    @Override
    public void writeRaw(byte[] data) throws IOException {
        writeRaw(new String(data, StandardCharsets.UTF_8));
    }

    @Override
    public void writeRaw(CharSequence data) throws IOException {
        writeRaw(data.toString());
    }

    @Override
    public void writeRaw(String data) {
        if (!closed) {
            synchronized (pending) {
                pending.append(data);
            }
        }
    }

    @Override
    public void onResize(Runnable action) {
    }

    @Override
    public void enableMouseCapture() throws IOException {
        writeRaw("\u001b[?1002h");
        flush();
    }

    @Override
    public void disableMouseCapture() throws IOException {
        writeRaw(MOUSE_OFF);
        flush();
    }

    /**
     * Answers the next code point as TamboUI's terminal input reader expects:
     * the code point itself, or a negative sentinel when the timeout expired
     * without data (mirroring how a real terminal driver reports idleness).
     */
    @Override
    public int read(int timeoutMillis) throws IOException {
        Integer value = takeOne(timeoutMillis, true);
        return value == null ? NO_DATA : value;
    }

    @Override
    public int peek(int timeoutMillis) throws IOException {
        Integer value = takeOne(timeoutMillis, false);
        return value == null ? NO_DATA : value;
    }

    private Integer takeOne(int timeoutMillis, boolean consume) throws IOException {
        synchronized (this) {
            if (peeked != null) {
                return swap(consume);
            }
            if (closed) {
                return null;
            }
            try {
                Integer value = input.poll(Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS);
                if (value == null) {
                    return null;
                }
                if (!consume) {
                    peeked = value;
                }
                return value;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for input", e);
            }
        }
    }

    private Integer swap(boolean consume) {
        Integer value = peeked;
        if (consume) {
            peeked = null;
        }
        return value;
    }

    /**
     * Writes the sequences that show the cursor and leave the alternate screen,
     * then marks the backend closed. The TamboUI runner already issues the same
     * restore before it closes the backend; repeating it here keeps the browser
     * terminal usable when the runner could not finish its own close. After
     * this call every write is dropped and queued input is discarded.
     */
    @Override
    public void close() throws IOException {
        try {
            writeRaw(CURSOR_SHOW);
            writeRaw(ALTERNATE_SCREEN_LEAVE);
            flush();
        } catch (IOException | RuntimeException e) {
            LOG.debug("browser terminal could not restore the screen on close", e);
        } finally {
            closed = true;
            input.clear();
        }
    }
}
