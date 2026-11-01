package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
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
import dev.omatheusmesmo.vintelli.api.capability.TerminalSession;
import dev.omatheusmesmo.vintelli.api.terminal.DangerousCommands;
import dev.omatheusmesmo.vintelli.api.terminal.TerminalCommandParser;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Local terminal of the terminal capability. The command line is handed to a
 * configured host shell, so pipes, redirects, globbing, variable expansion
 * and {@code cd} are interpreted by that shell. Every shell command requires
 * explicit confirmation before it runs.
 *
 * <p>Working directory policy: the requested initial directory is resolved
 * against the {@link LocalProjectRoot}; an initial directory outside that
 * root, through {@code ..} or behind a symlink, fails before a process starts.
 * After startup the shell may change directory and access the filesystem with
 * the current user's permissions. This is a starting-directory policy, not a
 * filesystem sandbox. A command that times out returns the output it produced
 * so far in the failure detail.
 */
@Unremovable
@Singleton
public final class LocalTerminalCapability implements TerminalCapability {

    private final LocalProjectRoot projectRoot;
    private final ProcessRunner runner;
    private final TerminalShellResolver shells;
    private final Duration defaultTimeout;
    private final Duration sessionIdleTimeout;

    @Inject
    LocalTerminalCapability(LocalProjectRoot projectRoot, ProcessRunner runner, TerminalShellResolver shells,
            @ConfigProperty(name = "vintelli.local.process-timeout", defaultValue = "60M") Duration timeout,
            @ConfigProperty(name = "vintelli.local.terminal-idle-timeout", defaultValue = "PT5M") Duration sessionIdleTimeout) {
        this.projectRoot = projectRoot;
        this.runner = runner;
        this.shells = shells;
        this.defaultTimeout = timeout;
        this.sessionIdleTimeout = sessionIdleTimeout;
    }

    /**
     * Non CDI factory for tests, mirroring {@link LocalRunCapability#of}.
     */
    public static LocalTerminalCapability of(LocalProjectRoot projectRoot, Duration timeout) {
        TerminalShellResolver shells = new TerminalShellResolver("auto", System.getProperty("os.name", ""),
                System.getenv());
        return new LocalTerminalCapability(projectRoot, new ProcessRunner(), shells, timeout, Duration.ofMinutes(5));
    }

    static LocalTerminalCapability of(LocalProjectRoot projectRoot, Duration timeout, TerminalShellResolver shells) {
        return new LocalTerminalCapability(projectRoot, new ProcessRunner(), shells, timeout, Duration.ofMinutes(5));
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
        try {
            TerminalCommandParser.parse(request.commandLine());
        } catch (IllegalArgumentException e) {
            return CapabilityResult.failed(new CapabilityFailure("The command line cannot be parsed into a program",
                    e.getMessage()));
        }
        // A shell can execute commands hidden in expansions, substitutions, or
        // compound statements. Checking just the first word cannot establish
        // that the rest of the line is safe, so require consent for the line.
        if (!request.confirmed()) {
            return CapabilityResult.ok(new TerminalResult(TerminalOutcome.REQUIRES_CONFIRMATION, -1, ""));
        }
        return run(request);
    }

    @Override
    public CapabilityResult<TerminalSession> startSession(TerminalSessionRequest request) {
        if (!request.confirmed()) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "Starting an interactive shell requires explicit confirmation",
                    "The shell can execute commands with the current user's privileges"));
        }
        Path inside = projectRoot.confine(request.workingDirectory());
        if (inside == null) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "The initial working directory is outside the project root or cannot be resolved",
                    request.workingDirectory().toString()));
        }
        try {
            return CapabilityResult.ok(LocalTerminalSession.start(inside, sessionIdleTimeout, shells.selected()));
        } catch (IOException failure) {
            String reason = failure.getMessage() != null && failure.getMessage().contains("script")
                    ? "The POSIX 'script' utility is required to start an interactive PTY"
                    : "Cannot start an interactive terminal session";
            return CapabilityResult.failed(new CapabilityFailure(reason, failure.getMessage()));
        }
    }

    @Override
    public boolean requiresSessionCommandConfirmation(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return false;
        }
        List<String> tokens;
        try {
            tokens = TerminalCommandParser.parse(commandLine);
        } catch (IllegalArgumentException ignored) {
            return true;
        }
        if (DangerousCommands.requiresConfirmation(tokens.getFirst(), tokens.subList(1, tokens.size()))) {
            return true;
        }
        return commandLine.contains(";") || commandLine.contains("&&") || commandLine.contains("||")
                || commandLine.contains("|") || commandLine.contains("$") || commandLine.contains("`");
    }

    private CapabilityResult<TerminalResult> run(TerminalRequest request) {
        Path inside = projectRoot.confine(request.workingDirectory());
        if (inside == null) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "The working directory is outside the project root or cannot be resolved",
                    request.workingDirectory().toString()));
        }
        List<String> command = shells.selected().oneShotCommand(request.commandLine());
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
