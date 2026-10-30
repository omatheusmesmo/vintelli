package dev.omatheusmesmo.vintelli.local;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalRequest;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Local terminal of the terminal capability. The parsed command reaches the
 * child process as separate argv entries, never as one string through a
 * shell: there is no {@code shell=true} anywhere in Vintelli, so an
 * unsanitised line cannot chain, glob or inject. Commands that control the
 * machine or erase data recursively come back as
 * {@code REQUIRES_CONFIRMATION} and only run when the caller reissues the
 * request carrying an explicit confirmation.
 *
 * <p>Working directory policy: every command runs inside the
 * {@link LocalProjectRoot}, with no opt-out. A relative working directory
 * resolves against the root, and any directory that lands outside it, through
 * {@code ..} or behind a symlink, fails before a process starts. A command that
 * times out returns the output it produced so far in the failure detail.
 *
 * <p>Known limits, deliberately kept for the interactive terminal to replace:
 * the line is not a shell, so pipes, redirects, globbing, variable expansion,
 * command chaining and {@code cd} are not interpreted; each word is passed as
 * one argument. The confirmation gate is a heuristic on the program name and
 * is documented on {@link DangerousCommands}.
 */
@Unremovable
@Singleton
public final class LocalTerminalCapability implements TerminalCapability {

    private final LocalProjectRoot projectRoot;
    private final ProcessRunner runner;
    private final Duration defaultTimeout;

    @Inject
    LocalTerminalCapability(LocalProjectRoot projectRoot, ProcessRunner runner,
            @ConfigProperty(name = "vintelli.local.process-timeout", defaultValue = "60M") Duration timeout) {
        this.projectRoot = projectRoot;
        this.runner = runner;
        this.defaultTimeout = timeout;
    }

    /**
     * Non CDI factory for tests, mirroring {@link LocalRunCapability#of}.
     */
    public static LocalTerminalCapability of(LocalProjectRoot projectRoot, Duration timeout) {
        return new LocalTerminalCapability(projectRoot, new ProcessRunner(), timeout);
    }

    @Override
    public String identifier() {
        return "local";
    }

    @Override
    public ProviderHealth health() {
        return ProviderHealth.available();
    }

    @Override
    public String provenance() {
        return "local shell process";
    }

    @Override
    public CapabilityResult<TerminalResult> execute(TerminalRequest request) {
        List<String> command;
        try {
            command = TerminalCommandParser.parse(request.commandLine());
        } catch (IllegalArgumentException e) {
            return CapabilityResult.failed(new CapabilityFailure("The command line cannot be parsed into a program",
                    e.getMessage()));
        }
        List<String> args = command.subList(1, command.size());
        if (!request.confirmed() && DangerousCommands.requiresConfirmation(command.get(0), args)) {
            return CapabilityResult.ok(new TerminalResult(TerminalOutcome.REQUIRES_CONFIRMATION, -1, ""));
        }
        return run(command, request);
    }

    private CapabilityResult<TerminalResult> run(List<String> command, TerminalRequest request) {
        Path inside = projectRoot.confine(request.workingDirectory());
        if (inside == null) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "The working directory is outside the project root or cannot be resolved",
                    request.workingDirectory().toString()));
        }
        ProcessRunner.ProcessResult result;
        try {
            result = runner.run(new ProcessRunner.CommandPlan(command, inside, Map.of(), defaultTimeout));
        } catch (ProcessRunner.UncheckedProcessFailure e) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot start the command", e.getMessage()));
        }
        return switch (result.outcome()) {
            case EXITED, CANCELLED -> CapabilityResult.ok(new TerminalResult(
                    TerminalOutcome.EXECUTED, result.exitCode().orElse(-1), result.output()));
            case TIMED_OUT -> CapabilityResult.failed(new CapabilityFailure(
                    "The command timed out", result.output()));
        };
    }
}
