package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Lifecycle owner for one child process exchanged with over stdio, shared by ACP
 * hosts and LSP servers: spawning, stderr capture, and bounded shutdown.
 *
 * <p>Callers build a {@link JsonRpcConnection} on top of {@link #stdout()} and
 * {@link #stdin()} with the framing the peer speaks. stderr is always drained on a
 * virtual thread so a chatty child can never block on a full stderr pipe; without a
 * listener the output is discarded, CRLF only output is trimmed to plain lines, and
 * single stderr lines beyond 64 KiB are truncated rather than buffered. The exit
 * listener runs exactly once, either when the child exits by itself or after
 * termination was forced.</p>
 */
public final class JsonRpcProcessSession implements AutoCloseable {

    /**
     * Notified once with the process exit code, whatever produced the exit.
     */
    @FunctionalInterface
    public interface ExitListener {

        void onExit(int exitCode);
    }

    /**
     * Child process launch description.
     *
     * @param command          executable plus arguments
     * @param environment      extra environment entries merged into the parent ones,
     *                         copied defensively at construction
     * @param workingDirectory working directory, null for the inherited one
     */
    public record ProcessSpec(List<String> command, Map<String, String> environment,
            Path workingDirectory) {

        public ProcessSpec {
            Objects.requireNonNull(command, "command");
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
            command = List.copyOf(command);
            environment = environment == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        }

        public static ProcessSpec of(List<String> command) {
            return new ProcessSpec(command, null, null);
        }
    }

    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(5);
    public static final Duration FORCE_KILL_GRACE = Duration.ofSeconds(2);

    private static final int STDERR_BUFFER_BYTES = 8192;
    private static final int MAX_STDERR_LINE_BYTES = 65536;
    private static final char LF = '\n';

    private final Process process;
    private final Consumer<String> stderrListener;
    private final ExitListener exitListener;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean shutDown = new AtomicBoolean(false);

    /**
     * Spawns the command described by the spec, optionally in its working directory
     * with extra environment entries, and starts stderr capture.
     */
    public static JsonRpcProcessSession spawn(ProcessSpec spec,
            Consumer<String> stderrListener, ExitListener exitListener) throws IOException {
        Objects.requireNonNull(spec, "spec");
        ProcessBuilder builder = new ProcessBuilder(spec.command());
        if (spec.workingDirectory() != null) {
            builder.directory(spec.workingDirectory().toFile());
        }
        if (spec.environment() != null) {
            builder.environment().putAll(spec.environment());
        }
        return new JsonRpcProcessSession(builder.start(), stderrListener, exitListener);
    }

    private JsonRpcProcessSession(Process process, Consumer<String> stderrListener,
            ExitListener exitListener) {
        this.process = Objects.requireNonNull(process, "process");
        this.stderrListener = stderrListener;
        this.exitListener = exitListener;
        executor.execute(this::drainStderr);
        executor.execute(this::awaitExit);
    }

    public OutputStream stdin() {
        return process.getOutputStream();
    }

    public InputStream stdout() {
        return process.getInputStream();
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Bounded shutdown in the order the ACP transports page describes: first stdin
     * is closed so a clean child exits on end of input, then the termination signal
     * is sent, and only a child that ignored both is destroyed forcibly together
     * with its descendants, so launcher grandchildren never keep the pipes open.
     *
     * @param gracePeriod time the child gets after the stdin close and again after
     *                    the termination signal
     */
    public void shutdown(Duration gracePeriod) {
        Objects.requireNonNull(gracePeriod, "gracePeriod");
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        closeStdin();
        if (awaitTermination(gracePeriod)) {
            return;
        }
        process.destroy();
        if (awaitTermination(gracePeriod)) {
            return;
        }
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        awaitTermination(FORCE_KILL_GRACE);
    }

    private void closeStdin() {
        try (OutputStream stdin = process.getOutputStream()) {
        } catch (IOException stdinCloseFailure) {
            /**
             * Best effort: an unwritable stdin cannot prevent the termination path.
             */
        }
    }

    @Override
    public void close() {
        shutdown(DEFAULT_SHUTDOWN_GRACE);
        executor.shutdown();
    }

    private boolean awaitTermination(Duration gracePeriod) {
        try {
            return process.waitFor(gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void drainStderr() {
        try ( InputStream stderr = process.getErrorStream()) {
            byte[] buffer = new byte[STDERR_BUFFER_BYTES];
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int chunk = stderr.read(buffer);
                if (chunk < 0) {
                    break;
                }
                for (int index = 0; index < chunk; index++) {
                    byte current = buffer[index];
                    if (current == LF) {
                        publishStderr(line);
                        line.reset();
                    } else if (line.size() < MAX_STDERR_LINE_BYTES) {
                        line.write(current);
                    }
                }
            }
            if (line.size() > 0) {
                publishStderr(line);
            }
        } catch (IOException stderrFailure) {
            if (stderrListener != null) {
                stderrListener.accept("stderr capture failed: " + stderrFailure.getMessage());
            }
        }
    }

    private void publishStderr(ByteArrayOutputStream line) {
        if (stderrListener == null) {
            return;
        }
        String text = line.toString(StandardCharsets.UTF_8);
        stderrListener.accept(stripCarriageReturn(text));
    }

    private static String stripCarriageReturn(String text) {
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    private void awaitExit() {
        try {
            int exitCode = process.waitFor();
            if (exitListener != null) {
                exitListener.onExit(exitCode);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
