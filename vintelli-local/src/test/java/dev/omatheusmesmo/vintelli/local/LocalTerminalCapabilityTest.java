package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalOutcome;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalRequest;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalResult;
import dev.omatheusmesmo.vintelli.api.capability.TerminalSession;

/**
 * The terminal provider requires confirmation and executes the command line
 * through the host shell. All assertions are platform neutral.
 */
class LocalTerminalCapabilityTest {

    @TempDir
    Path temp;

    private LocalTerminalCapability capability() {
        return LocalTerminalCapability.of(new LocalProjectRoot(temp), Duration.ofMinutes(5));
    }

    /*
     * Single file source launcher line of the form
     * {@code java /abs/Ping.java args}, where tokens with spaces stay inside
     * one shell-quoted argument, interpreted by the host shell.
     */
    private static String launch(Path source, String... args) {
        List<String> tokens = new ArrayList<>();
        tokens.add(TestProcesses.javaExecutable().toString());
        tokens.add(source.toString());
        tokens.addAll(List.of(args));
        return tokens.stream().map(LocalTerminalCapabilityTest::quoted).reduce((a, b) -> a + " " + b).orElse("");
    }

    private static String quoted(String token) {
        if (!token.strip().contains(" ")) {
            return token;
        }
        String quote = TestProcesses.isWindows() ? "\"" : "'";
        return quote + token + quote;
    }

    @Test
    void executesAParsedCommandLineAndReturnsItsOutcome() {
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest(launch(ping, "hello-token", "two joined words"), temp, true)));

        assertEquals(TerminalOutcome.EXECUTED, result.outcome());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("arg:hello-token"), result.output());
        assertTrue(result.output().contains("arg:two joined words"), result.output());
        assertTrue(result.output().contains("stderr:ping"));
    }

    @Test
    void demandsConfirmationForSessionAndMachineControlCommands() {
        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest("sudo rm whatever", temp, false)));

        assertEquals(TerminalOutcome.REQUIRES_CONFIRMATION, result.outcome());
        assertEquals(-1, result.exitCode());
        assertEquals("", result.output());
    }

    @Test
    void demandsConfirmationForARecursiveForcedRemoval() {
        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest("rm -rf build", temp, false)));

        assertEquals(TerminalOutcome.REQUIRES_CONFIRMATION, result.outcome());
    }

    @Test
    void letsAConfirmedDangerousCommandReachExecution() {
        assumeFalse(TestProcesses.isWindows(), "The stand-in removal needs a POSIX rm");

        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest("rm -rf nothing-for-test", temp, true)));

        assertEquals(TerminalOutcome.EXECUTED, result.outcome());
        assertEquals(0, result.exitCode());
    }

    @Test
    void refusesToStartAnInteractiveSessionWithoutExplicitConfirmation() {
        var result = capability().startSession(new TerminalCapability.TerminalSessionRequest(temp, false));

        assertTrue(result.isFailure());
        assertTrue(result.failureOf().orElseThrow().reason().contains("requires explicit confirmation"));
    }

    @Test
    void requiresConfirmationForRecursiveRemovalOnTheHostShell() {
        String command = TestProcesses.isWindows()
                ? "rmdir /s /q \"" + temp.toAbsolutePath() + "\""
                : "rm -rf '" + temp.toAbsolutePath() + "'";

        assertTrue(capability().requiresSessionCommandConfirmation(command));
    }

    @Test
    void keepsAnInteractiveShellAcrossCommandsAndAllowsChangingItsInitialDirectory() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        String shellId = TestProcesses.isWindows() ? "cmd" : "sh";
        TerminalShellResolver shells = new TerminalShellResolver(shellId, System.getProperty("os.name", ""),
                System.getenv());
        LocalTerminalCapability provider = LocalTerminalCapability.of(new LocalProjectRoot(project),
                Duration.ofMinutes(5), shells);
        var started = provider.startSession(new TerminalCapability.TerminalSessionRequest(Path.of("."), true));
        assertFalse(started.isFailure(), started.failureOf().map(Object::toString).orElse(""));
        TerminalSession session = ((CapabilityResult.Ok<TerminalSession>) started).value();
        try {
            String projectMarker = "VINTELLI_CWD_" + UUID.randomUUID();
            String outsideMarker = "VINTELLI_CWD_" + UUID.randomUUID();
            String projectOutput = project.toRealPath().toString();
            String outsideOutput = outside.toRealPath().toString();
            session.write((workingDirectoryCommand(projectMarker) + "\r").getBytes(StandardCharsets.UTF_8));
            awaitOutput(session, projectOutput, projectMarker);

            session.write((changeDirectoryCommand(outside) + "\r").getBytes(StandardCharsets.UTF_8));
            session.write((workingDirectoryCommand(outsideMarker) + "\r").getBytes(StandardCharsets.UTF_8));
            awaitOutput(session, outsideOutput, outsideMarker);
        } finally {
            session.close();
        }
    }

    private static String workingDirectoryCommand(String marker) {
        return TestProcesses.isWindows()
                ? "echo %CD% & echo " + marker
                : "pwd; echo " + marker;
    }

    private static String changeDirectoryCommand(Path directory) throws IOException {
        String value = directory.toRealPath().toString();
        if (TestProcesses.isWindows()) {
            return "cd /d \"" + value + "\"";
        }
        return "cd '" + value.replace("'", "'\\''") + "'";
    }

    private static boolean outputContainsPath(TerminalSession session, String expected) {
        String normalizedExpected = normalizePath(expected);
        for (String line : session.output()) {
            if (normalizePath(line.strip()).endsWith(normalizedExpected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean outputContainsLine(TerminalSession session, String expected) {
        return session.output().stream().map(String::strip)
                .anyMatch(line -> line.equals(expected));
    }

    private static boolean outputContainsMarker(TerminalSession session, String marker, String expectedPath) {
        if (!TestProcesses.isWindows()) {
            return outputContainsLine(session, marker);
        }
        // cmd.exe may append its next prompt to the command's last output line.
        // The echoed input ends with the marker, while the real echo is followed
        // by the prompt for the current directory.
        String output = normalizePath(String.join("", session.output()));
        String markerAndPrompt = normalizePath(marker + expectedPath + ">");
        return output.contains(markerAndPrompt);
    }

    private static void awaitOutput(TerminalSession session, String expectedPath, String marker) {
        try {
            TestProcesses.awaitUntil(Duration.ofSeconds(10),
                    () -> outputContainsPath(session, expectedPath) && outputContainsMarker(session, marker, expectedPath));
        } catch (AssertionError timeout) {
            throw new AssertionError("Timed out waiting for " + marker + " and path " + expectedPath
                    + "; terminal output was: " + session.output(), timeout);
        }
    }

    private static String normalizePath(String path) {
        return TestProcesses.isWindows()
                ? path.replace('/', '\\').toLowerCase(Locale.ROOT)
                : path;
    }

    @Test
    void carriesANonZeroExitAsAnExecutedOutcomeWithItsCode() {
        Path failer = TestProcesses.extractSource(temp, "Failer.java");

        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest(launch(failer), temp, true)));

        assertEquals(TerminalOutcome.EXECUTED, result.outcome());
        assertEquals(3, result.exitCode());
        assertTrue(result.output().contains("failing:now"));
    }

    @Test
    void confirmedShellCommandInterpretsRedirection() throws Exception {
        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest("echo shell-output > output.txt", temp, true)));

        assertEquals(TerminalOutcome.EXECUTED, result.outcome());
        assertEquals(0, result.exitCode());
        assertEquals("shell-output", Files.readString(temp.resolve("output.txt")).strip());
    }

    @Test
    void shellCompoundCommandsRequireConfirmationBeforeExecution() {
        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest("echo harmless; rm -rf build", temp, false)));

        assertEquals(TerminalOutcome.REQUIRES_CONFIRMATION, result.outcome());
    }

    @Test
    void shellExpansionsRequireConfirmationBeforeExecution() {
        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest("echo $(rm -rf build)", temp, false)));

        assertEquals(TerminalOutcome.REQUIRES_CONFIRMATION, result.outcome());
    }

    @Test
    void reportsAMissingExecutableAsANonZeroExitInsteadOfASpawnFailure() {
        assumeFalse(TestProcesses.isWindows(), "Shell error wording differs on Windows");

        var result = capability().execute(new TerminalRequest("vintelli-no-such-executable", temp, true));

        assertFalse(result.isFailure());
        TerminalResult terminalResult = ((CapabilityResult.Ok<TerminalResult>) result).value();
        assertEquals(TerminalOutcome.EXECUTED, terminalResult.outcome());
        assertEquals(127, terminalResult.exitCode());
    }

    @Test
    void rejectsAnUnparsableCommandLineInsteadOfGuessing() {
        var result = capability().execute(new TerminalRequest("echo 'open quote", temp, true));

        assertTrue(result.isFailure());
        var failure = assertInstanceOf(CapabilityResult.Failed.class, result).failure();
        assertTrue(failure.reason().contains("cannot be parsed into a program"));
    }

    @Test
    void refusesAWorkingDirectoryOutsideTheProjectRoot() throws IOException {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        var result = LocalTerminalCapability.of(new LocalProjectRoot(project), Duration.ofMinutes(5))
                .execute(new TerminalRequest(launch(ping), outside, true));

        assertTrue(result.isFailure());
        assertTrue(result.failureOf().orElseThrow().reason().contains("outside the project root"));
    }

    @Test
    void refusesAWorkingDirectoryReachedThroughADotDotSegment() throws IOException {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(temp.resolve("outside"));
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        var result = LocalTerminalCapability.of(new LocalProjectRoot(project), Duration.ofMinutes(5))
                .execute(new TerminalRequest(launch(ping), Path.of("..", "outside"), true));

        assertTrue(result.isFailure());
        assertTrue(result.failureOf().orElseThrow().reason().contains("outside the project root"));
    }

    @Test
    void refusesASymlinkWorkingDirectoryThatLeavesTheProjectRoot() throws IOException {
        assumeFalse(TestProcesses.isWindows(), "Creating symbolic links needs a privilege on Windows");
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path link = Files.createSymbolicLink(project.resolve("escape"), outside);
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        var result = LocalTerminalCapability.of(new LocalProjectRoot(project), Duration.ofMinutes(5))
                .execute(new TerminalRequest(launch(ping), link, true));

        assertTrue(result.isFailure());
        assertTrue(result.failureOf().orElseThrow().reason().contains("outside the project root"));
    }

    @Test
    void refusesASiblingDirectoryWhoseNameStartsWithTheProjectRootName() throws IOException {
        Path project = Files.createDirectories(temp.resolve("root"));
        Path evil = Files.createDirectories(temp.resolve("root-evil"));
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        var result = LocalTerminalCapability.of(new LocalProjectRoot(project), Duration.ofMinutes(5))
                .execute(new TerminalRequest(launch(ping), evil, true));

        assertTrue(result.isFailure());
        assertTrue(result.failureOf().orElseThrow().reason().contains("outside the project root"));
    }

    @Test
    void refusesWindowsDriveRelativeAndOtherDriveWorkingDirectories() {
        assumeTrue(TestProcesses.isWindows(), "Windows path semantics required");
        Path project = temp.toAbsolutePath().normalize();
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        for (String value : List.of("C:foo", "D:\\outside", "\\\\server\\share\\outside")) {
            var result = LocalTerminalCapability.of(new LocalProjectRoot(project), Duration.ofMinutes(5))
                    .execute(new TerminalRequest(launch(ping), Path.of(value), true));
            assertTrue(result.isFailure(), value);
            assertTrue(result.failureOf().orElseThrow().reason().contains("outside the project root"), value);
        }
    }

    @Test
    void refusesAnAbsentChildOfADanglingSymlinkInsideTheProjectRoot() throws IOException {
        assumeFalse(TestProcesses.isWindows(), "Creating symbolic links needs a privilege on Windows");
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.createSymbolicLink(project.resolve("dangling"), temp.resolve("does-not-exist"));
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        var result = LocalTerminalCapability.of(new LocalProjectRoot(project), Duration.ofMinutes(5))
                .execute(new TerminalRequest(launch(ping), project.resolve("dangling").resolve("child"), true));

        assertTrue(result.isFailure());
        assertTrue(result.failureOf().orElseThrow().reason().contains("outside the project root"));
    }

    @Test
    void resolvesARelativeWorkingDirectoryInsideTheProjectRoot() throws IOException {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path nested = Files.createDirectories(project.resolve("nested"));
        Path ping = TestProcesses.extractSource(temp, "Ping.java");

        TerminalResult result = resultOf(LocalTerminalCapability.of(new LocalProjectRoot(project), Duration.ofMinutes(5))
                .execute(new TerminalRequest(launch(ping), Path.of("nested"), true)));

        assertEquals(TerminalOutcome.EXECUTED, result.outcome());
        assertTrue(result.output().toLowerCase(Locale.ROOT)
                .contains(nested.toRealPath().toString().toLowerCase(Locale.ROOT)), result.output());
    }

    @Test
    void keepsTheOutputReadSoFarWhenTheCommandTimesOut() throws IOException {
        Path pumper = TestProcesses.extractSource(temp, "Pumper.java");

        var result = LocalTerminalCapability.of(new LocalProjectRoot(temp), Duration.ofSeconds(5))
                .execute(new TerminalRequest(launch(pumper, "100"), temp, true));

        assertTrue(result.isFailure());
        var failure = assertInstanceOf(CapabilityResult.Failed.class, result).failure();
        assertTrue(failure.reason().contains("timed out"), failure.reason());
        assertTrue(failure.detail().contains("working:"), failure.detail());
    }

    private static TerminalResult resultOf(CapabilityResult<TerminalResult> result) {
        assertFalse(result.isFailure(), result.failureOf().map(failure -> failure.toString()).orElse(""));
        return ((CapabilityResult.Ok<TerminalResult>) result).value();
    }
}
