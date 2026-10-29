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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalOutcome;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalRequest;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalResult;

/**
 * The terminal provider parses the line into argv entries, gates dangerous
 * commands behind an explicit confirmation and executes everything else as a
 * real short process. All assertions are platform neutral.
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
     * one quoted argv entry, parsed by the provider like any user line.
     */
    private static String launch(Path source, String... args) {
        List<String> tokens = new ArrayList<>();
        tokens.add(TestProcesses.javaExecutable().toString());
        tokens.add(source.toString());
        tokens.addAll(List.of(args));
        return tokens.stream().map(LocalTerminalCapabilityTest::quoted).reduce((a, b) -> a + " " + b).orElse("");
    }

    private static String quoted(String token) {
        return token.strip().contains(" ") ? "'" + token + "'" : token;
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
    void carriesANonZeroExitAsAnExecutedOutcomeWithItsCode() {
        Path failer = TestProcesses.extractSource(temp, "Failer.java");

        TerminalResult result = resultOf(capability().execute(
                new TerminalRequest(launch(failer), temp, true)));

        assertEquals(TerminalOutcome.EXECUTED, result.outcome());
        assertEquals(3, result.exitCode());
        assertTrue(result.output().contains("failing:now"));
    }

    @Test
    void reportsASpawnThatCannotHappenAsAFailedResult() {
        var result = capability().execute(new TerminalRequest("vintelli-no-such-executable", temp, true));

        assertTrue(result.isFailure());
        assertTrue(result.failureOf().orElseThrow().reason().contains("Cannot start"));
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
