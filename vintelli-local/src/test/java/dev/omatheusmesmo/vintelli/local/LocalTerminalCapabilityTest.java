package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    private static TerminalResult resultOf(CapabilityResult<TerminalResult> result) {
        assertFalse(result.isFailure(), result.failureOf().map(failure -> failure.toString()).orElse(""));
        return ((CapabilityResult.Ok<TerminalResult>) result).value();
    }
}
