package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real process behaviour of the local provider core: live output streaming,
 * merged standard and error output, exit reporting, working directories,
 * environment handover, cancellation and the timeout that terminates a
 * process. Everything runs through the java launcher of the test JVM, so the
 * same assertions hold on Linux, macOS and Windows.
 */
class ProcessRunnerTest {

    private static final Duration GENEROUS = Duration.ofMinutes(5);
    private static final Duration STREAM_SETTLE = Duration.ofSeconds(60);
    private static final Path MODULE_DIRECTORY = Path.of(System.getProperty("user.dir"));

    @TempDir
    Path temp;

    @Test
    void streamsEveryOutputLineWhileTheProcessRuns() {
        Path ping = TestProcesses.extractSource(temp, "Ping.java");
        List<String> observed = new CopyOnWriteArrayList<>();
        ProcessRunner runner = new ProcessRunner();

        ProcessRunner.Handle handle = runner.start(plan(command(ping, "first-arg", "second-arg")), observed::add);
        TestProcesses.awaitUntil(STREAM_SETTLE, () -> observed.contains("done"));
        handle.cancel();

        assertFalse(observed.isEmpty());
        assertTrue(observed.contains("arg:first-arg"));
        assertTrue(observed.contains("arg:second-arg"));
        assertTrue(observed.contains("mark:unset"));
        assertTrue(observed.contains("stderr:ping"));
        assertTrue(observed.contains("done"));
    }

    @Test
    void reportsTheExitCodeAndTheMergedStreamsOfACompletedProcess() {
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        ProcessRunner.ProcessResult result = new ProcessRunner().run(plan(command(ping)));

        assertEquals(ProcessRunner.ProcessOutcome.EXITED, result.outcome());
        assertEquals(0, result.exitCode().orElseThrow());
        assertTrue(result.outputLines().contains("done"));
        assertTrue(result.outputLines().contains("stderr:ping"));
    }

    @Test
    void boundsKeptLinesAndLineLengthAndKeepsDrainingThePipe() {
        Path flooder = TestProcesses.extractSource(temp, "Flooder.java");

        ProcessRunner.ProcessResult result = new ProcessRunner().run(
                new ProcessRunner.CommandPlan(command(flooder), temp, Map.of(), Duration.ofMinutes(1)));

        List<String> lines = result.outputLines();
        assertEquals(ProcessRunner.ProcessOutcome.EXITED, result.outcome());
        assertEquals(ProcessRunner.MAX_OUTPUT_LINES + 1, lines.size());
        assertEquals(ProcessRunner.OUTPUT_TRUNCATED_MARKER, lines.getLast());
        assertEquals(ProcessRunner.MAX_LINE_LENGTH + 1 + ProcessRunner.LINE_TRUNCATED_MARKER.length(),
                lines.getFirst().length());
        assertTrue(lines.getFirst().endsWith(" " + ProcessRunner.LINE_TRUNCATED_MARKER));
    }

    @Test
    void runsInTheRequestedWorkingDirectory() throws IOException {
        Path ping = TestProcesses.extractSource(temp, "Ping.java");
        Path working = temp.resolve("elsewhere");
        Files.createDirectories(working);

        ProcessRunner.ProcessResult result = new ProcessRunner().run(
                new ProcessRunner.CommandPlan(command(ping), working, Map.of(), GENEROUS));

        String childWorkingDirectory = result.outputLines().stream()
                .filter(line -> line.startsWith("wd:"))
                .findFirst()
                .orElseThrow();
        String expected = working.toRealPath().toString();
        assertTrue(childWorkingDirectory.equals("wd:" + expected)
                || childWorkingDirectory.equalsIgnoreCase("wd:" + working.toString()),
                childWorkingDirectory + " of " + expected);
    }

    @Test
    void handsRequestedEnvironmentEntriesToTheChild() {
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        ProcessRunner.ProcessResult result = new ProcessRunner().run(
                new ProcessRunner.CommandPlan(command(ping), temp, Map.of("VINTELLI_LOCAL_TEST_MARK", "wheel"),
                        GENEROUS));

        assertTrue(result.outputLines().contains("mark:wheel"), result.outputLines().toString());
    }

    @Test
    void treatsShellOperatorsAsPlainArgumentsWithoutAShell() {
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        ProcessRunner.ProcessResult result = new ProcessRunner().run(plan(command(ping, "clean; rm -rf .")));

        assertEquals(ProcessRunner.ProcessOutcome.EXITED, result.outcome());
        assertTrue(result.outputLines().contains("arg:clean; rm -rf ."), result.outputLines().toString());
        assertTrue(result.outputLines().contains("done"));
    }

    @Test
    void cancelsALongRunningProcessAndStopsTheOutputStream() {
        Path pumper = TestProcesses.extractSource(temp, "Pumper.java");
        List<String> observed = new CopyOnWriteArrayList<>();
        ProcessRunner runner = new ProcessRunner();

        ProcessRunner.Handle handle = runner.start(plan(command(pumper)), observed::add);
        TestProcesses.awaitUntil(STREAM_SETTLE, () -> !observed.isEmpty());

        handle.cancel();
        TestProcesses.awaitUntil(STREAM_SETTLE, () -> {
            int settled = observed.size();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return observed.size() == settled;
        });
    }

    @Test
    void terminatesAProcessThatRunsLongerThanItsTimeout() {
        Path pumper = TestProcesses.extractSource(temp, "Pumper.java");

        ProcessRunner.ProcessResult result = new ProcessRunner().run(
                new ProcessRunner.CommandPlan(command(pumper, "100"), temp, Map.of(), Duration.ofSeconds(12)));

        assertEquals(ProcessRunner.ProcessOutcome.TIMED_OUT, result.outcome());
        assertFalse(result.outputLines().isEmpty());
    }

    @Test
    void cancellationAlsoStopsTheDescendantsOfTheProcess() throws Exception {
        Path spawner = TestProcesses.extractSource(temp, "Spawner.java");
        Path pumper = TestProcesses.extractSource(temp, "Pumper.java");
        List<String> observed = new CopyOnWriteArrayList<>();
        ProcessRunner runner = new ProcessRunner();

        ProcessRunner.CommandPlan spawnerPlan = new ProcessRunner.CommandPlan(
                command(spawner, pumper.toString()), MODULE_DIRECTORY, Map.of(), GENEROUS);
        ProcessRunner.Handle handle = runner.start(spawnerPlan, observed::add);
        TestProcesses.awaitUntil(STREAM_SETTLE, () -> observed.stream().anyMatch(line -> line.startsWith("child:")));
        ProcessHandle spawnerProcess = processNamedBy(observed, "spawner:");
        ProcessHandle childProcess = processNamedBy(observed, "child:");
        assertTrue(childProcess.isAlive());

        handle.cancel();

        spawnerProcess.onExit().get(STREAM_SETTLE.toSeconds(), TimeUnit.SECONDS);
        childProcess.onExit().get(STREAM_SETTLE.toSeconds(), TimeUnit.SECONDS);
        assertFalse(childProcess.isAlive());
    }

    @Test
    void endsTheRunWhenTheParentExitsEvenIfADescendantStillHoldsThePipe() throws IOException {
        Path detacher = TestProcesses.extractSource(temp, "Detacher.java");
        Path sleeper = TestProcesses.extractSource(temp, "Sleeper.java");
        Path pidFile = temp.resolve("descendant.pid");
        ProcessRunner runner = new ProcessRunner(Duration.ofSeconds(1));
        long started = System.nanoTime();

        ProcessRunner.ProcessResult result = null;
        try {
            result = runner.run(new ProcessRunner.CommandPlan(
                    command(detacher, sleeper.toString(), pidFile.toString()), temp, Map.of(), GENEROUS));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            String observed = result.outcome() + " exit=" + result.exitCode() + " in " + elapsed
                    + " lines=" + result.outputLines();
            assertEquals(ProcessRunner.ProcessOutcome.EXITED, result.outcome(), observed);
            assertEquals(0, result.exitCode().orElseThrow(), observed);
            assertTrue(result.outputLines().stream().anyMatch(line -> line.startsWith("child:")), observed);
            assertTrue(result.outputLines().contains("detached"), observed);
            assertTrue(elapsed.compareTo(Duration.ofSeconds(20)) < 0, observed);
        } finally {
            if (Files.exists(pidFile)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip()))
                        .ifPresent(ProcessRunnerTest::destroyAndWait);
            }
        }
    }

    @Test
    void endsTheGracePathWithTheTerminationCallbackAfterTheParentOutput() throws IOException {
        Path detacher = TestProcesses.extractSource(temp, "Detacher.java");
        Path sleeper = TestProcesses.extractSource(temp, "Sleeper.java");
        Path pidFile = temp.resolve("descendant.pid");
        List<String> observed = new CopyOnWriteArrayList<>();
        ProcessRunner runner = new ProcessRunner(Duration.ofSeconds(1));

        ProcessRunner.Handle handle = runner.start(
                new ProcessRunner.CommandPlan(command(detacher, sleeper.toString(), pidFile.toString()), temp, Map.of(), GENEROUS),
                observed::add, termination -> observed.add("RUN_TERMINATED:" + termination.outcome()));
        try {
            TestProcesses.awaitUntil(STREAM_SETTLE, () -> observed.contains("RUN_TERMINATED:EXITED"));
            assertTrue(observed.stream().anyMatch(line -> line.startsWith("child:")), observed.toString());
            assertEquals("RUN_TERMINATED:EXITED", observed.getLast(), observed.toString());
        } finally {
            handle.cancel();
            if (Files.exists(pidFile)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip()))
                        .ifPresent(ProcessRunnerTest::destroyAndWait);
            }
        }
    }

    private static void destroyAndWait(ProcessHandle process) {
        process.destroyForcibly();
        try {
            process.onExit().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the descendant process to exit", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("Descendant process did not exit after forcible termination", e);
        }
    }

    @Test
    void completesAtOnceWhenTheOutputEndsInsteadOfWaitingForTheDrainGrace() {
        Duration longGrace = Duration.ofSeconds(30);
        ProcessRunner runner = new ProcessRunner(longGrace);
        long started = System.nanoTime();

        ProcessRunner.ProcessResult result = runner.run(
                new ProcessRunner.CommandPlan(echoCommand(), temp, Map.of(), GENEROUS));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertEquals(ProcessRunner.ProcessOutcome.EXITED, result.outcome());
        assertTrue(result.outputLines().contains("vintelli-echo"), result.outputLines().toString());
        assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0, "took " + elapsed);
    }

    @Test
    void abandonsAPipeThatStaysSilentAfterTheParentExitedAndClosesIt() {
        OpenPipe pipe = OpenPipe.silent("partial\n");
        List<String> observed = new CopyOnWriteArrayList<>();
        ProcessRunner runner = new ProcessRunner(Duration.ofMillis(500), process -> pipe);

        runner.start(new ProcessRunner.CommandPlan(echoCommand(), temp, Map.of(), GENEROUS),
                observed::add, termination -> observed.add("RUN_TERMINATED:" + termination.outcome()));

        TestProcesses.awaitUntil(Duration.ofSeconds(20), () -> observed.contains("RUN_TERMINATED:EXITED"));
        assertEquals(List.of("partial", "RUN_TERMINATED:EXITED"), observed);
        TestProcesses.awaitUntil(Duration.ofSeconds(10), pipe::isClosed);
    }

    @Test
    void doesNotAnnounceTheTerminationWhileALineIsStillBeingDelivered() throws InterruptedException {
        OpenPipe pipe = OpenPipe.silent("in-flight\n");
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch terminated = new CountDownLatch(1);
        CountDownLatch sinkEntered = new CountDownLatch(1);
        boolean[] announcedDuringDelivery = new boolean[1];
        ProcessRunner runner = new ProcessRunner(Duration.ofMillis(300), process -> pipe);

        runner.start(new ProcessRunner.CommandPlan(echoCommand(), temp, Map.of(), GENEROUS),
                line -> {
                    sinkEntered.countDown();
                    try {
                        announcedDuringDelivery[0] = terminated.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    events.add("line:" + line);
                },
                termination -> {
                    events.add("RUN_TERMINATED:" + termination.outcome());
                    terminated.countDown();
                });

        assertTrue(sinkEntered.await(20, TimeUnit.SECONDS));
        assertTrue(terminated.await(20, TimeUnit.SECONDS));
        assertFalse(announcedDuringDelivery[0]);
        assertEquals(List.of("line:in-flight", "RUN_TERMINATED:EXITED"), events);
    }

    @Test
    void keepsDrainingWhenALineSinkThrows() {
        OpenPipe pipe = OpenPipe.silent("first\nsecond\n");
        List<String> events = new CopyOnWriteArrayList<>();
        ProcessRunner runner = new ProcessRunner(Duration.ofMillis(300), process -> pipe);

        runner.start(new ProcessRunner.CommandPlan(echoCommand(), temp, Map.of(), GENEROUS),
                line -> {
                    if (line.equals("first")) {
                        throw new IllegalStateException("sink failure");
                    }
                    events.add(line);
                }, termination -> events.add("RUN_TERMINATED:" + termination.outcome()));

        TestProcesses.awaitUntil(Duration.ofSeconds(20), () -> events.contains("RUN_TERMINATED:EXITED"));
        assertEquals(List.of("second", "RUN_TERMINATED:EXITED"), events);
    }

    @Test
    void returnsTheBoundedLinesOfAFloodedPipeAfterTheAbandonment() {
        OpenPipe pipe = OpenPipe.flooding(true);
        ProcessRunner runner = new ProcessRunner(Duration.ofMillis(300), process -> pipe);

        ProcessRunner.ProcessResult result = runner.run(
                new ProcessRunner.CommandPlan(echoCommand(), temp, Map.of(), GENEROUS));

        assertEquals(ProcessRunner.ProcessOutcome.EXITED, result.outcome());
        assertEquals(ProcessRunner.MAX_OUTPUT_LINES + 1, result.outputLines().size());
        assertEquals(ProcessRunner.OUTPUT_TRUNCATED_MARKER, result.outputLines().getLast());
        TestProcesses.awaitUntil(Duration.ofSeconds(10), pipe::isClosed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void endsTheRunAtTheHardCapWhenThePipeNeverPausesWithOrWithoutNewlines(boolean withNewlines) {
        OpenPipe pipe = OpenPipe.flooding(withNewlines);
        List<String> observed = new CopyOnWriteArrayList<>();
        ProcessRunner runner = new ProcessRunner(Duration.ofMillis(500), process -> pipe);

        runner.start(new ProcessRunner.CommandPlan(echoCommand(), temp, Map.of(), GENEROUS),
                line -> {
                }, termination -> observed.add("RUN_TERMINATED:" + termination.outcome()));

        TestProcesses.awaitUntil(Duration.ofSeconds(20), () -> observed.contains("RUN_TERMINATED:EXITED"));
        TestProcesses.awaitUntil(Duration.ofSeconds(10), pipe::isClosed);
    }

    @Test
    void deliversTheUnterminatedLastLineAndBoundsEveryLineAcrossChunks() {
        ProcessRunner.LineAssembler assembler = new ProcessRunner.LineAssembler();
        List<String> lines = new ArrayList<>();
        char[] first = "one\r\ntw".toCharArray();
        char[] second = ("o\n" + "y".repeat(ProcessRunner.MAX_LINE_LENGTH + 5) + "\nlast").toCharArray();

        assembler.accept(first, first.length, lines::add);
        assembler.accept(second, second.length, lines::add);
        assembler.flush(lines::add);

        assertEquals(List.of("one", "two", "y".repeat(ProcessRunner.MAX_LINE_LENGTH) + " "
                + ProcessRunner.LINE_TRUNCATED_MARKER, "last"), lines);
    }

    @Test
    void failsFastWhenTheProgramDoesNotExist() {
        ProcessRunner runner = new ProcessRunner();

        ProcessRunner.CommandPlan doomed = new ProcessRunner.CommandPlan(
                List.of("vintelli-no-such-executable"), temp, Map.of(), GENEROUS);

        ProcessRunner.UncheckedProcessFailure failure = assertThrows(
                ProcessRunner.UncheckedProcessFailure.class, () -> runner.run(doomed));
        assertTrue(failure.getMessage().contains("vintelli-no-such-executable"));
    }

    private static List<String> echoCommand() {
        if (TestProcesses.isWindows()) {
            return List.of("cmd", "/c", "echo vintelli-echo");
        }
        return List.of("sh", "-c", "echo vintelli-echo");
    }

    /**
     * Stand-in for the output pipe of a process whose background descendant
     * still holds it open: it never reports the end of the stream. Closing it
     * wakes a pending read with an exception, which is stricter than a Linux
     * pipe, where closing does not wake a read already blocked.
     */
    private static final class OpenPipe extends InputStream {

        private final byte[] preloaded;
        private final boolean flooding;
        private final boolean newlines;
        private final CountDownLatch closed = new CountDownLatch(1);
        private int position;

        private OpenPipe(byte[] preloaded, boolean flooding, boolean newlines) {
            this.preloaded = preloaded;
            this.flooding = flooding;
            this.newlines = newlines;
        }

        static OpenPipe silent(String preloaded) {
            return new OpenPipe(preloaded.getBytes(StandardCharsets.UTF_8), false, false);
        }

        static OpenPipe flooding(boolean newlines) {
            return new OpenPipe(new byte[0], true, newlines);
        }

        boolean isClosed() {
            return closed.getCount() == 0;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0];
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (isClosed()) {
                throw new IOException("Stream closed");
            }
            if (position < preloaded.length) {
                int count = Math.min(length, preloaded.length - position);
                System.arraycopy(preloaded, position, buffer, offset, count);
                position += count;
                return count;
            }
            if (flooding) {
                for (int index = 0; index < length; index++) {
                    buffer[offset + index] = newlines && index % 64 == 63 ? (byte) '\n' : (byte) 'x';
                }
                return length;
            }
            try {
                closed.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Stream closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private List<String> command(Path source, String... args) {
        List<String> command = new ArrayList<>();
        command.add(TestProcesses.javaExecutable().toString());
        command.add(source.toString());
        command.addAll(List.of(args));
        return command;
    }

    private static ProcessHandle processNamedBy(List<String> observed, String prefix) {
        long pid = Long.parseLong(observed.stream().filter(line -> line.startsWith(prefix))
                .findFirst().orElseThrow().substring(prefix.length()));
        return ProcessHandle.of(pid).orElseThrow();
    }

    private ProcessRunner.CommandPlan plan(List<String> command) {
        return new ProcessRunner.CommandPlan(command, temp, Map.of(), GENEROUS);
    }
}
