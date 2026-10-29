package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                new ProcessRunner.CommandPlan(command(pumper, "100"), temp, Map.of(), Duration.ofMillis(4000)));

        assertEquals(ProcessRunner.ProcessOutcome.TIMED_OUT, result.outcome());
        assertFalse(result.outputLines().isEmpty());
    }

    @Test
    void cancellationAlsoStopsTheDescendantsOfTheProcess() {
        Path spawner = TestProcesses.extractSource(temp, "Spawner.java");
        Path pumper = TestProcesses.extractSource(temp, "Pumper.java");
        List<String> observed = new CopyOnWriteArrayList<>();
        ProcessRunner runner = new ProcessRunner();

        ProcessRunner.Handle handle = runner.start(plan(command(spawner, pumper.toString())), observed::add);
        TestProcesses.awaitUntil(STREAM_SETTLE, () -> observed.stream().anyMatch(line -> line.startsWith("child:")));
        long childPid = Long.parseLong(observed.stream().filter(line -> line.startsWith("child:"))
                .findFirst().orElseThrow().substring("child:".length()));
        assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));

        handle.cancel();

        TestProcesses.awaitUntil(STREAM_SETTLE, () -> !ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
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

    private List<String> command(Path source, String... args) {
        List<String> command = new ArrayList<>();
        command.add(TestProcesses.javaExecutable().toString());
        command.add(source.toString());
        command.addAll(List.of(args));
        return command;
    }

    private ProcessRunner.CommandPlan plan(List<String> command) {
        return new ProcessRunner.CommandPlan(command, temp, Map.of(), GENEROUS);
    }
}
