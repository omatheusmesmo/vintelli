package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import dev.omatheusmesmo.vintelli.api.capability.TerminalSession;

/** A bounded interactive child shell owned by one terminal window. */
final class LocalTerminalSession implements TerminalSession {

    static final int MAX_OUTPUT_LINES = 1_000;
    static final int MAX_LINE_LENGTH = 2_000;
    static final int MAX_INPUT_BYTES = 16_384;
    static final int DEFAULT_COLUMNS = 80;
    static final int DEFAULT_ROWS = 24;
    private static final Pattern ANSI_CSI = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern ANSI_OSC = Pattern.compile("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)");

    private final Process process;
    private final PtyProcess ptyProcess;
    private final OutputStream input;
    private final Thread reader;
    private final Thread idleWatch;
    private final long idleTimeoutMillis;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final StringBuilder currentLine = new StringBuilder();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastInputMillis = System.currentTimeMillis();
    private int lastColumns = DEFAULT_COLUMNS;
    private int lastRows = DEFAULT_ROWS;
    private volatile boolean escape;
    private volatile boolean csi;
    private volatile boolean osc;
    private volatile boolean oscEscape;

    private LocalTerminalSession(Process process, PtyProcess ptyProcess, Duration idleTimeout) {
        this.process = process;
        this.ptyProcess = ptyProcess;
        this.input = process.getOutputStream();
        this.idleTimeoutMillis = idleTimeout == null ? 0 : idleTimeout.toMillis();
        this.reader = new Thread(this::readOutput, "vintelli-terminal-output-" + process.pid());
        this.reader.setDaemon(true);
        this.reader.start();
        this.idleWatch = new Thread(this::watchIdle, "vintelli-terminal-idle-" + process.pid());
        this.idleWatch.setDaemon(true);
        this.idleWatch.start();
    }

    static LocalTerminalSession start(Path workingDirectory, Duration idleTimeout, TerminalShell shell)
            throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("TERM", "dumb");
        if (shell.kind() == TerminalShell.Kind.POSIX) {
            environment.put("PS1", "vintelli> ");
        }
        if (windows) {
            PtyProcess pty = new PtyProcessBuilder(shell.interactiveCommand().toArray(String[]::new))
                    .setDirectory(workingDirectory.toString())
                    .setEnvironment(environment)
                    .setInitialColumns(DEFAULT_COLUMNS)
                    .setInitialRows(DEFAULT_ROWS)
                    .setWindowsAnsiColorEnabled(true)
                    .setUseWinConPty(true)
                    .setRedirectErrorStream(true)
                    .start();
            return new LocalTerminalSession(pty, pty, idleTimeout);
        }
        ProcessBuilder processBuilder = new ProcessBuilder(unixPtyCommand(shell))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        return new LocalTerminalSession(process, null, idleTimeout);
    }

    private static List<String> unixPtyCommand(TerminalShell shell) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("bsd")) {
            ArrayList<String> command = new ArrayList<>(List.of("script", "-q", "/dev/null"));
            command.addAll(shell.interactiveCommand());
            return List.copyOf(command);
        }
        return List.of("script", "-q", "-e", "-f", "-c", quotePosixCommand(shell.interactiveCommand()),
                "/dev/null");
    }

    static String quotePosixCommand(List<String> command) {
        return command.stream()
                .map(argument -> "'" + argument.replace("'", "'\\''") + "'")
                .collect(Collectors.joining(" "));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public void write(byte[] bytes) {
        if (closed.get() || bytes == null || bytes.length == 0) {
            return;
        }
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Terminal input exceeds " + MAX_INPUT_BYTES + " bytes");
        }
        try {
            synchronized (input) {
                input.write(bytes);
                input.flush();
            }
            lastInputMillis = System.currentTimeMillis();
        } catch (IOException failure) {
            appendLine("[terminal input failed: " + failure.getMessage() + "]");
            close();
        }
    }

    @Override
    public synchronized List<String> output() {
        ArrayList<String> result = new ArrayList<>(lines);
        if (!currentLine.isEmpty()) {
            result.add(clean(currentLine.toString()));
        }
        return List.copyOf(result);
    }

    @Override
    public void resize(int columns, int rows) {
        if (ptyProcess == null) {
            return;
        }
        int width = Math.clamp(columns, 20, 400);
        int height = Math.clamp(rows, 5, 200);
        if (width != lastColumns || height != lastRows) {
            ptyProcess.setWinSize(new WinSize(width, height));
            lastColumns = width;
            lastRows = height;
        }
    }

    @Override
    public boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    private void readOutput() {
        try (InputStreamReader stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2_048];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                lastInputMillis = System.currentTimeMillis();
                for (int index = 0; index < count; index++) {
                    accept(buffer[index]);
                }
            }
            synchronized (this) {
                if (!currentLine.isEmpty()) {
                    addLine(currentLine.toString());
                    currentLine.setLength(0);
                }
            }
        } catch (IOException failure) {
            if (!closed.get()) {
                appendLine("[terminal output failed: " + failure.getMessage() + "]");
            }
        }
    }

    private synchronized void accept(char character) {
        if (osc) {
            if (oscEscape && character == '\\') {
                osc = false;
            }
            oscEscape = character == '\u001b';
            if (character == '\u0007') {
                osc = false;
            }
            return;
        }
        if (csi) {
            if (character >= '@' && character <= '~') {
                csi = false;
            }
            return;
        }
        if (escape) {
            escape = false;
            if (character == '[') {
                csi = true;
            } else if (character == ']') {
                osc = true;
            }
            return;
        }
        if (character == '\u001b') {
            escape = true;
        } else if (character == '\r' || character == '\n') {
            if (character == '\n' || !currentLine.isEmpty()) {
                addLine(currentLine.toString());
                currentLine.setLength(0);
            }
        } else if (character == '\b' || character == '\u007f') {
            if (!currentLine.isEmpty()) {
                currentLine.setLength(currentLine.length() - 1);
            }
        } else if (!Character.isISOControl(character)) {
            currentLine.append(character);
            if (currentLine.length() > MAX_LINE_LENGTH) {
                currentLine.setLength(MAX_LINE_LENGTH);
            }
        }
    }

    private synchronized void appendLine(String text) {
        addLine(text);
    }

    private void addLine(String line) {
        lines.addLast(clean(line));
        while (lines.size() > MAX_OUTPUT_LINES) {
            lines.removeFirst();
        }
    }

    private static String clean(String text) {
        return ANSI_OSC.matcher(ANSI_CSI.matcher(text).replaceAll("")).replaceAll("");
    }

    private void watchIdle() {
        if (idleTimeoutMillis <= 0) {
            return;
        }
        while (!closed.get() && process.isAlive()) {
            if (System.currentTimeMillis() - lastInputMillis >= idleTimeoutMillis) {
                appendLine("[terminal session closed after inactivity]");
                close();
                return;
            }
            try {
                Thread.sleep(Math.min(1_000, idleTimeoutMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        idleWatch.interrupt();
        try {
            input.close();
        } catch (IOException ignored) {
        }
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (UnsupportedOperationException ignored) {
            // Some PTY-backed processes (e.g. pty4j's WinConPtyProcess) do not
            // expose a ProcessHandle; fall back to destroying the root process.
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        if (ptyProcess != null) {
            ptyProcess.destroy();
        }
    }
}
