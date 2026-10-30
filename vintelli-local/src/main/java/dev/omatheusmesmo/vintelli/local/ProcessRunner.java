package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The only place inside Vintelli that spawns local processes. Every execution
 * is an argument list against a working directory with added environment
 * entries, never a shell command line: the program and each argument reach the
 * child as separate argv entries, so an unsanitised string is never handed to
 * a shell.
 *
 * <p>Every run carries a timeout on the child's {@code onExit} future: when
 * the child still runs at expiry, the child and its descendants are destroyed,
 * the outcome reports the timeout and the output read so far is kept. Output
 * is read from a single merged pipe, either collected or streamed line by line
 * while the process runs. Output is bounded: at most {@link #MAX_OUTPUT_LINES}
 * lines are kept, each cut at {@link #MAX_LINE_LENGTH} characters, and the rest
 * is drained and dropped so the child never blocks on a full pipe. Cancellation
 * destroys the process tree as well, so children that spawn their own children
 * cannot outlive a cancelled run.
 *
 * <p>A dedicated daemon reader performs blocking reads of bounded chunks of the
 * merged pipe, so the end of the stream is observed the moment it happens.
 * Once the parent has exited, the run completes as soon as the reader sees that
 * end. When a background grandchild keeps the pipe open instead, the run
 * completes when the output stays idle for {@link #DRAIN_GRACE} or when
 * {@link #DRAIN_GRACE} has elapsed since the parent exited, whichever comes
 * first; the hard cap on the wait for the pipe holds whether the output arrives
 * as lines, as a flood or
 * with no line terminator at all, because the reader never waits for a newline
 * and a separate watcher enforces both limits. The lines of a descendant that
 * keeps writing afterwards are dropped, the stream is closed, the reader
 * stops when its pending read returns, and the descendant is destroyed only
 * where the platform still lets the parent reach it, because a process
 * orphaned by an exited parent is reparented and no longer listed as a
 * descendant on Linux. On Linux, closing the stream does not wake a read that
 * is already blocked on the pipe, so the daemon reader can stay parked until
 * the last descendant writes or closes its end; the run itself is not held.
 * The caps bound the wait on the pipe only, not the time spent inside a sink:
 * a sink that blocks holds the sink lock, so the abandon path waits for it and
 * the run can complete only after that sink returns.
 *
 * <p>Lines are handed to the sinks one at a time under a dedicated lock, and
 * the termination hook only runs once that lock was taken and every later line
 * refused, so no line reaches a sink after the hook and the hook never overtakes
 * a line in flight.
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

    private static final System.Logger LOGGER = System.getLogger(ProcessRunner.class.getName());
    private static final int FIRST_THREAD = 1;
    private static final int READ_CHUNK_CHARACTERS = 8_192;

    /**
     * Idle cap and hard cap of the drain once the parent process has exited
     * while a descendant still holds the pipe open: the run completes when the
     * output stays idle for this long, or when this much time has elapsed
     * since the parent exited, whichever comes first. When the pipe reaches its
     * end sooner the run completes at once. After the cap the run completes
     * with the parent's own outcome and the lines of the descendant are
     * dropped.
     */
    static final Duration DRAIN_GRACE = Duration.ofSeconds(5);

    private final Duration drainGrace;
    private final Function<Process, InputStream> outputOf;

    /**
     * Stateless executor bean: every thread it spawns is a daemon and the
     * execution state lives in the returned handle, not here.
     */
    @Inject
    ProcessRunner() {
        this(DRAIN_GRACE);
    }

    ProcessRunner(Duration drainGrace) {
        this(drainGrace, Process::getInputStream);
    }

    /**
     * Test seam: {@code outputOf} supplies the stream the output reader
     * consumes, which lets a test hold the pipe open the way a background
     * descendant does, on every platform and without racing the JDK.
     */
    ProcessRunner(Duration drainGrace, Function<Process, InputStream> outputOf) {
        this.drainGrace = drainGrace;
        this.outputOf = outputOf;
    }

    /**
     * Runs the plan to termination, collecting the bounded output lines, and
     * terminates the process tree if the timeout expires first.
     *
     * @throws UncheckedProcessFailure when the process cannot start
     */
    public ProcessResult run(CommandPlan plan) {
        Execution execution = Execution.begin(plan, drainGrace, outputOf, termination -> {
        });
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        execution.pumpLines(lines::add);
        execution.awaitCompletion();
        synchronized (lines) {
            return new ProcessResult(execution.outcome(), Optional.ofNullable(execution.exitCode()), lines);
        }
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
        Execution execution = Execution.begin(plan, drainGrace, outputOf, onTermination);
        execution.pumpLines(outputLines);
        return execution;
    }

    /**
     * Turns chunks of characters into bounded lines. A line keeps at most
     * {@link #MAX_LINE_LENGTH} characters, the characters past the limit are
     * consumed and dropped up to the line terminator, so a single huge line
     * never fills the heap. Carriage returns are dropped, which also strips
     * the CR of CRLF endings. Unlike a blocking line read it never waits for a
     * terminator, so a producer that writes no newline cannot stall the caller.
     */
    static final class LineAssembler {

        private final StringBuilder line = new StringBuilder();
        private boolean truncated;
        private boolean readAny;

        void accept(char[] chunk, int length, Consumer<String> lines) {
            for (int index = 0; index < length; index++) {
                char next = chunk[index];
                readAny = true;
                if (next == '\n') {
                    lines.accept(take());
                } else if (next != '\r') {
                    if (line.length() < MAX_LINE_LENGTH) {
                        line.append(next);
                    } else {
                        truncated = true;
                    }
                }
            }
        }

        void flush(Consumer<String> lines) {
            if (readAny) {
                lines.accept(take());
            }
        }

        private String take() {
            String result = truncated ? line + " " + LINE_TRUNCATED_MARKER : line.toString();
            line.setLength(0);
            truncated = false;
            readAny = false;
            return result;
        }
    }

    private static final class Execution implements Handle {

        private final CommandPlan plan;
        private final Process process;
        private final InputStream output;
        private final Consumer<ProcessTermination> onTermination;
        private final Duration drainGrace;
        private final CountDownLatch completed = new CountDownLatch(FIRST_THREAD);
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<Consumer<String>> lineSinks = new CopyOnWriteArrayList<>();
        private int storedLines;
        private boolean outputTruncated;
        private final CountDownLatch drainFinished = new CountDownLatch(FIRST_THREAD);
        private volatile long lastDataNanos = System.nanoTime();
        private boolean parentExited;
        private long parentExitNanos;
        private final Object sinkLock = new Object();
        private boolean sinksClosed;
        private boolean drainEnded;
        private boolean abandoned;
        private boolean settled;
        private volatile ProcessOutcome outcome;
        private volatile Integer exitCode;

        private Execution(CommandPlan plan, Duration drainGrace, Function<Process, InputStream> outputOf,
                Consumer<ProcessTermination> onTermination) {
            this.plan = plan;
            this.drainGrace = drainGrace;
            this.onTermination = onTermination;
            this.process = launch(plan);
            this.output = outputOf.apply(process);
            watchForTermination();
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

        private static Execution begin(CommandPlan plan, Duration drainGrace,
                Function<Process, InputStream> outputOf, Consumer<ProcessTermination> onTermination) {
            return new Execution(plan, drainGrace, outputOf, onTermination);
        }

        /**
         * The exit future of the child drives the run: the timeout fires on a
         * copy of it while the child still runs, and the parent exit settles
         * the run once the output is drained or the grace after the exit
         * expires.
         */
        private void watchForTermination() {
            process.onExit().thenRun(this::parentExited);
            process.onExit().orTimeout(plan.timeout().toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((exited, timeoutFailure) -> {
                        if (timeoutFailure != null && !cancelled.get()) {
                            timedOut.set(true);
                            destroyTree();
                        }
                    });
        }

        private void parentExited() {
            boolean drained;
            synchronized (this) {
                parentExited = true;
                parentExitNanos = System.nanoTime();
                drained = drainEnded;
            }
            if (drained) {
                settle();
                return;
            }
            Thread watcher = new Thread(this::enforceDrainBound, "vintelli-process-drain-" + process.pid());
            watcher.setDaemon(true);
            watcher.start();
        }

        private void markDrainEnded() {
            synchronized (this) {
                drainEnded = true;
            }
            drainFinished.countDown();
        }

        /**
         * Runs only when the parent exited before the reader saw the end of
         * the pipe. Waits for that end, and abandons the output once the idle
         * cap or the hard cap since the parent exit is reached, however the
         * output arrives. An interruption abandons it as well, so the run can
         * never hang.
         */
        private void enforceDrainBound() {
            long graceNanos = drainGrace.toNanos();
            long exitNanos;
            synchronized (this) {
                exitNanos = parentExitNanos;
            }
            try {
                while (true) {
                    long remaining = Math.min(lastDataNanos, exitNanos) + graceNanos - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    if (drainFinished.await(remaining, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            abandonOutput();
        }

        private void abandonOutput() {
            synchronized (this) {
                if (drainEnded) {
                    return;
                }
                abandoned = true;
            }
            settle();
            closeOutput();
        }

        private void closeOutput() {
            try {
                output.close();
            } catch (IOException failure) {
                LOGGER.log(System.Logger.Level.DEBUG, "The process output could not be closed", failure);
            }
        }

        /**
         * Completes the run once the parent exited and either the output
         * reached its end or the grace after the exit ran out. In the second
         * case a descendant still holds the pipe: later lines are dropped, the
         * tree is destroyed as far as the parent still reaches it, and the
         * termination reports the parent's own outcome.
         */
        private void settle() {
            boolean drained;
            synchronized (this) {
                if (settled || !parentExited || !(drainEnded || abandoned)) {
                    return;
                }
                settled = true;
                drained = drainEnded;
                if (!drained) {
                    abandoned = true;
                }
                outcome = resolveOutcome();
                exitCode = safeExitValue();
            }
            if (!drained) {
                destroyTree();
            }
            closeSinks();
            onTermination.accept(new ProcessTermination(outcome, Optional.ofNullable(exitCode)));
            completed.countDown();
        }

        /**
         * Waits for the line being dispatched, if any, and refuses every later
         * one, so the termination hook is never overtaken by an in-flight
         * line. It uses its own lock and never the execution monitor.
         */
        private void closeSinks() {
            synchronized (sinkLock) {
                sinksClosed = true;
            }
        }

        private ProcessOutcome resolveOutcome() {
            if (timedOut.get()) {
                return ProcessOutcome.TIMED_OUT;
            }
            if (cancelled.get()) {
                return ProcessOutcome.CANCELLED;
            }
            return ProcessOutcome.EXITED;
        }

        private void pumpLines(Consumer<String> firstLines) {
            lineSinks.add(firstLines);
            Thread pumper = new Thread(this::drainOutput, "vintelli-process-output-" + process.pid());
            pumper.setDaemon(true);
            pumper.start();
        }

        private void drainOutput() {
            LineAssembler assembler = new LineAssembler();
            char[] chunk = new char[READ_CHUNK_CHARACTERS];
            try (Reader reader = new InputStreamReader(output, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(chunk)) != -1) {
                    lastDataNanos = System.nanoTime();
                    assembler.accept(chunk, read, this::deliver);
                    lastDataNanos = System.nanoTime();
                    if (isAbandoned()) {
                        return;
                    }
                }
                assembler.flush(this::deliver);
                markDrainEnded();
            } catch (IOException failure) {
                markDrainEnded();
                destroyTree();
            } finally {
                settle();
            }
        }

        private synchronized boolean isAbandoned() {
            return abandoned;
        }

        private void deliver(String line) {
            boolean sendLine = false;
            boolean sendTruncated = false;
            synchronized (this) {
                if (abandoned) {
                    return;
                }
                if (storedLines < MAX_OUTPUT_LINES) {
                    storedLines++;
                    sendLine = true;
                } else if (!outputTruncated) {
                    outputTruncated = true;
                    sendTruncated = true;
                }
            }
            if (sendLine) {
                dispatch(line);
            }
            if (sendTruncated) {
                dispatch(OUTPUT_TRUNCATED_MARKER);
            }
        }

        private void dispatch(String line) {
            synchronized (sinkLock) {
                if (sinksClosed) {
                    return;
                }
                for (Consumer<String> sink : lineSinks) {
                    try {
                        sink.accept(line);
                    } catch (RuntimeException failure) {
                        LOGGER.log(System.Logger.Level.WARNING, "A line sink failed and was skipped", failure);
                    }
                }
            }
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
