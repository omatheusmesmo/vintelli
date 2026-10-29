package dev.omatheusmesmo.vintelli.cli;

import dev.omatheusmesmo.vintelli.acp.EchoAcpAgentResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Output round trip of the AEsh command layer through the Quarkus command
 * mode launcher: every assertion is on what the printed CLI wrote and the exit
 * code it mapped. The commands must stay POSIX shaped, so every non success
 * result keeps an explicit exit code the caller can script against.
 * {@link VintelliCliCommandsIT} replays the very same launches against the
 * packaged artifact, including the native image.
 */
@QuarkusMainTest
@WithTestResource(value = EchoAcpAgentResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliCliCommandsTest {

    @Test
    @Launch("inspect")
    void inspectPrintsTheSelectionAndTheCapabilityRows(LaunchResult result) {
        assertTrue(result.exitCode() == 0, result.getOutput());
        assertTrue(result.getOutput().contains("Shell: tool window '', file ''"), result.getOutput());
        assertTrue(result.getOutput().contains("run: local ("), result.getOutput());
        assertTrue(result.getOutput().contains("terminal: local (local shell process)"), result.getOutput());
        assertTrue(result.getOutput().contains("read-file: "), result.getOutput());
        assertTrue(result.getOutput().contains("diagnostics: unavailable: "), result.getOutput());
    }

    @Test
    @Launch(value = {"problems", "src/Main.java"}, exitCode = 1)
    void problemsReportsTheUnavailableDiagnosticsCapability(LaunchResult result) {
        assertTrue(result.getOutput().contains("Problems capability is unavailable: "), result.getOutput());
        assertTrue(result.getOutput().contains("'diagnostics'"), result.getOutput());
    }

    @Test
    @Launch(value = {"run"}, exitCode = 0)
    void runWithoutANameListsWhatCanRun(LaunchResult result) {
        assertTrue(result.getOutput().contains("maven:") || result.getOutput().contains("No run configurations"),
                result.getOutput());
    }

    @Test
    @Launch(value = {"run", "vintelli-unknown"}, exitCode = 1)
    void unknownRunConfigurationFailsWithTheAvailableNames(LaunchResult result) {
        assertTrue(result.getOutput().contains("Unknown run configuration 'vintelli-unknown'"), result.getOutput());
    }

    @Test
    @Launch(value = {"terminal", "sudo", "reboot"}, exitCode = 1)
    void gatedTerminalCommandsAskForExplicitConfirmation(LaunchResult result) {
        assertTrue(result.getOutput().contains("Vintelli asks for explicit confirmation"), result.getOutput());
        assertTrue(result.getOutput().contains("terminal --yes sudo reboot"), result.getOutput());
    }

    @Test
    @Launch(value = {"terminal", "java", "--version"}, exitCode = 0)
    void terminalRunsTheCommandThroughTheSelectedProvider(LaunchResult result) {
        assertTrue(result.getOutput().contains("openjdk"), result.getOutput());
    }

    @Test
    @Launch(value = {"agents", "prompt", "echo:hello"}, exitCode = 0)
    void agentPromptCompletesTheSessionRoundTrip(LaunchResult result) {
        assertTrue(result.getOutput().contains("echo:hello"), result.getOutput());
        assertTrue(result.getOutput().contains("Stop reason: end_turn"), result.getOutput());
    }

    @Test
    @Launch(value = {"agents"}, exitCode = 2)
    void bareAgentsCommandPointsAtThePromptUsage(LaunchResult result) {
        assertTrue(result.getOutput().contains("agents prompt"), result.getOutput());
    }

    @Test
    @Launch(value = {"run", "--help"}, exitCode = 0)
    void commandHelpPrintsItsUsageAndExitsZero(LaunchResult result) {
        assertTrue(result.getOutput().contains("Usage: run"), result.getOutput());
    }

    @Test
    @Launch(value = {"help", "inspect"}, exitCode = 0)
    void helpCommandPrintsTheNamedCommandUsage(LaunchResult result) {
        assertTrue(result.getOutput().contains("Usage: inspect"), result.getOutput());
    }

    @Test
    @Launch(value = {"help"}, exitCode = 0)
    void bareHelpListsTheAvailableCommands(LaunchResult result) {
        assertTrue(result.getOutput().contains("Available commands: "), result.getOutput());
        assertTrue(result.getOutput().contains("agents"), result.getOutput());
    }

    @Test
    @Launch(value = {"help", "definitely-not-a-command"}, exitCode = 127)
    void helpForAnUnknownCommandFailsWithThePosixCode(LaunchResult result) {
        assertTrue(result.getErrorOutput().contains("Unknown command: definitely-not-a-command"),
                result.getErrorOutput());
    }

    @Test
    @Launch(value = {"definitely-not-a-command"}, exitCode = 127)
    void unknownCommandFailsTheLaunchWithThePosixCode(LaunchResult result) {
        assertTrue(result.getErrorOutput().contains("Unknown command: definitely-not-a-command"),
                result.getErrorOutput());
    }
}
