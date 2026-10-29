package dev.omatheusmesmo.vintelli.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The only place inside Vintelli that spawns local processes. Every execution
 * is an argument list against a working directory with added environment
 * entries, never a shell command line: the program and each argument reach the
 * child as separate argv entries, so an unsanitised string is never handed to
 * a shell.
 *
 * <p>Every run carries a timeout: when it expires, the child and its
 * descendants are destroyed and the outcome reports the timeout. Output is
 * read from a single merged pipe, either collected or streamed line by line
 * while the process runs. Output is bounded: at most {@link #MAX_OUTPUT_LINES}
 * lines are kept, each cut at {@link #MAX_LINE_LENGTH} characters, and the rest
 * is drained and dropped so the child never blocks on a full pipe. Cancellation
 * destroys the process tree as well, so children that spawn their own children
 * cannot outlive a cancelled run.
 */
@Singleton
public final class ProcessRunner {

    static final int MAX_OUTPUT_LINES = 10_000;
    static final int MAX_LINE_LENGTH = 2_000;
    static final String LINE_TRUNCATED_MARKER = "[line truncated]";
    static final String OUTPUT_TRUNCATED_MARKER = "[output truncated: further lines were dropped]";

    /**
     * How one executed process ended. The exit code is the child's own value
     * whenever it terminated on its own whatever the outcome; a destroyed
     * process may report no exit code at all.
     */
    public enum ProcessOutcome {
        EXITED,
        CANCELLED,
        TIMED_OUT
    }

    /**
     * Command, working directory, environment additions and timeout of one
     * process execution. The command is an argv list: the first entry is the
     * program, the following entries its arguments.
     *
     * @param command          program and arguments, never a shell command line
     * @param workingDirectory directory the child starts in
     * @param environment      entries added to the inherited environment, no
     *                         entry removed
     * @param timeout          time the child may run before it is terminated
     */
    public record CommandPlan(List<String> command, Path workingDirectory,
            Map<String, String> environment, Duration timeout) {

        public CommandPlan {
            if (command == null || command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) {
                throw new IllegalArgumentException("A command plan always names a program");
            }
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("A command plan always carries a positive timeout");
            }
            command = List.copyOf(command);
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }

    /**
     * Final state of one completed execution: the outcome, the exit code when
     * the child reported one, and every output line in order.
     */
    public record ProcessResult(ProcessOutcome outcome, Optional<Integer> exitCode, List<String> outputLines) {

        public ProcessResult {
            Objects.requireNonNull(outcome, "outcome");
            outputLines = outputLines == null ? List.of() : List.copyOf(outputLines);
        }

        /**
         * @return the collected output lines separated by the platform
         *         independent newline of this JVM
         */
        public String output() {
            return String.join(System.lineSeparator(), outputLines);
        }
    }

    /**
     * Outcome and exit code of a process, delivered once to the termination
     * hook of {@link ProcessRunner#start} after every output line was passed
     * to the line consumer.
     */
    public record ProcessTermination(ProcessOutcome outcome, Optional<Integer> exitCode) {

        public ProcessTermination {
            Objects.requireNonNull(outcome, "outcome");
            exitCode = exitCode == null ? Optional.empty() : exitCode;
        }
    }

    /**
     * Handle of one started process: terminating it stops the child and its
     * descendants as soon as possible. Calling it on an already finished
     * process is a harmless no op, and repeated calls are safe.
     */
    public interface Handle {

        /**
         * Asks for quick termination of the process tree.
         */
        void cancel();
    }

    /**
     * Runtime failure of the spawn step itself, for example when the program
     * does not exist or the working directory is unreachable. Callers of the
     * capabilities turn this into a failed {@code CapabilityResult}; it is
     * never a programming error later in the lifecycle.
     */
    public static final class UncheckedProcessFailure extends RuntimeException {

        public UncheckedProcessFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int FIRST_THREAD = 1;

    /**
     * Stateless executor bean: every thread it spawns is a daemon and the
     * execution state lives in the returned handle, not here.
     */
    @Inject
    ProcessRunner() {
    }

    /**
     * Runs the plan to termination, collecting the bounded output lines, and
     * terminates the process tree if the timeout expires first.
     *
     * @throws UncheckedProcessFailure when the process cannot start
     */
    public ProcessResult run(CommandPlan plan) {
        Execution execution = Execution.begin(plan, termination -> {
        });
        List<String> lines = new ArrayList<>();
        execution.pumpLines(lines::add);
        execution.awaitCompletion();
        return new ProcessResult(execution.outcome(), Optional.ofNullable(execution.exitCode()), lines);
    }

    /**
     * Starts the plan and streams the bounded output lines into the consumer
     * while the process runs. The returned handle stops the run.
     *
     * @throws UncheckedProcessFailure when the process cannot start
     */
    public Handle start(CommandPlan plan, Consumer<String> outputLines) {
        return start(plan, outputLines, termination -> {
        });
    }

    /**
     * Starts the plan and streams the bounded output lines into the consumer
     * while the process runs. Once the process has terminated and every line
     * was delivered, the termination hook receives the outcome exactly once,
     * so a caller can append a final line after the output.
     *
     * @throws UncheckedProcessFailure when the process cannot start
     */
    public Handle start(CommandPlan plan, Consumer<String> outputLines, Consumer<ProcessTermination> onTermination) {
        Objects.requireNonNull(outputLines, "outputLines");
        Objects.requireNonNull(onTermination, "onTermination");
        Execution execution = Execution.begin(plan, onTermination);
        execution.pumpLines(outputLines);
        return execution;
    }

    private static final class Execution implements Handle {

        private final CommandPlan plan;
        private final Process process;
        private final Consumer<ProcessTermination> onTermination;
        private final CountDownLatch completed = new CountDownLatch(FIRST_THREAD);
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<Consumer<String>> lineSinks = new CopyOnWriteArrayList<>();
        private int storedLines;
        private boolean outputTruncated;
        private volatile ProcessOutcome outcome;
        private volatile Integer exitCode;

        private Execution(CommandPlan plan, Consumer<ProcessTermination> onTermination) {
            this.plan = plan;
            this.onTermination = onTermination;
            this.process = launch(plan);
            Thread watchdog = new Thread(this::enforceTimeout, "vintelli-process-watch-" + process.pid());
            watchdog.setDaemon(true);
            watchdog.start();
        }

        private static Process launch(CommandPlan plan) {
            ProcessBuilder builder = new ProcessBuilder(plan.command());
            builder.directory(plan.workingDirectory().toFile());
            builder.environment().putAll(plan.environment());
            builder.redirectErrorStream(true);
            try {
                Process started = builder.start();
                started.getOutputStream().close();
                return started;
            } catch (IOException failure) {
                throw new UncheckedProcessFailure(
                        "Cannot start '" + plan.command().get(0) + "': " + failure.getMessage(), failure);
            }
        }

        private static Execution begin(CommandPlan plan, Consumer<ProcessTermination> onTermination) {
            return new Execution(plan, onTermination);
        }

        private void enforceTimeout() {
            try {
                if (!process.waitFor(plan.timeout().toMillis(), TimeUnit.MILLISECONDS) && !cancelled.get()) {
                    timedOut.set(true);
                    destroyTree();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyTree();
            }
        }

        private void pumpLines(Consumer<String> firstLines) {
            lineSinks.add(firstLines);
            Thread pumper = new Thread(this::drainOutput, "vintelli-process-output-" + process.pid());
            pumper.setDaemon(true);
            pumper.start();
        }

        private void drainOutput() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(reader)) != null) {
                    deliver(line);
                }
            } catch (IOException e) {
                destroyTree();
            } finally {
                try {
                    awaitAfterEof();
                    finish();
                    onTermination.accept(new ProcessTermination(outcome, Optional.ofNullable(exitCode)));
                } finally {
                    completed.countDown();
                }
            }
        }

        private void deliver(String line) {
            if (storedLines < MAX_OUTPUT_LINES) {
                storedLines++;
                dispatch(line);
            } else if (!outputTruncated) {
                outputTruncated = true;
                dispatch(OUTPUT_TRUNCATED_MARKER);
            }
        }

        private void dispatch(String line) {
            for (Consumer<String> sink : lineSinks) {
                sink.accept(line);
            }
        }

        /**
         * Reads one line of at most {@link #MAX_LINE_LENGTH} characters. The
         * characters past the limit are consumed and dropped up to the line
         * terminator, so a single huge line never fills the heap. Carriage
         * returns are dropped, which also strips the CR of CRLF endings.
         *
         * @return the line, or null at the end of the stream
         */
        static String readBoundedLine(Reader reader) throws IOException {
            StringBuilder line = new StringBuilder();
            boolean truncated = false;
            boolean readAny = false;
            int next;
            while ((next = reader.read()) != -1) {
                readAny = true;
                if (next == '\n') {
                    break;
                }
                if (next == '\r') {
                    continue;
                }
                if (line.length() < MAX_LINE_LENGTH) {
                    line.append((char) next);
                } else {
                    truncated = true;
                }
            }
            if (!readAny) {
                return null;
            }
            return truncated ? line + " " + LINE_TRUNCATED_MARKER : line.toString();
        }

        private void awaitAfterEof() {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyTree();
            }
        }

        private void finish() {
            if (outcome != null) {
                return;
            }
            ProcessOutcome resolved;
            if (timedOut.get()) {
                resolved = ProcessOutcome.TIMED_OUT;
            } else if (cancelled.get()) {
                resolved = ProcessOutcome.CANCELLED;
            } else {
                resolved = ProcessOutcome.EXITED;
            }
            exitCode = safeExitValue();
            outcome = resolved;
        }

        private Integer safeExitValue() {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException e) {
                return null;
            }
        }

        ProcessOutcome outcome() {
            return outcome;
        }

        Integer exitCode() {
            return exitCode;
        }

        private void awaitCompletion() {
            try {
                completed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            destroyTree();
        }

        private void destroyTree() {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            process.toHandle().destroyForcibly();
        }
    }
}
