package dev.omatheusmesmo.vintelli.idea;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.terminal.DangerousCommands;
import dev.omatheusmesmo.vintelli.api.terminal.TerminalCommandParser;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Unremovable
@Singleton
public final class IdeaTerminalCapability implements TerminalCapability {

    private final IdeaMcpClient client;

    @Inject
    IdeaTerminalCapability(IdeaMcpClient client) {
        this.client = client;
    }

    @Override
    public String identifier() {
        return "idea";
    }

    @Override
    public ProviderHealth health() {
        ConnectionStatus current = client.status();
        if (current.state() == ConnectionState.CONNECTED) {
            return ProviderHealth.available();
        }
        return ProviderHealth.unavailable(current.state().name().toLowerCase() + ": " + current.detail());
    }

    @Override
    public String provenance() {
        return "IDEA MCP terminal";
    }

    @Override
    public CapabilityResult<TerminalResult> execute(TerminalRequest request) {
        String raw = request.commandLine();
        // Compound/substitution syntax (semicolons, &&, ||, pipes, command
        // substitution, backticks) is executed by IDEA in a real shell, so the
        // single-token DangerousCommands gate is insufficient. Treat any such
        // line as requiring confirmation when not already confirmed.
        if (!request.confirmed() && containsShellControlSyntax(raw)) {
            return CapabilityResult.ok(new TerminalResult(TerminalOutcome.REQUIRES_CONFIRMATION, 0, ""));
        }
        List<String> command;
        try {
            command = TerminalCommandParser.parse(raw);
        } catch (IllegalArgumentException e) {
            return CapabilityResult.failed(new CapabilityFailure("The command line cannot be parsed into a program",
                    e.getMessage()));
        }
        List<String> args = command.subList(1, command.size());
        if (!request.confirmed() && DangerousCommands.requiresConfirmation(command.get(0), args)) {
            return CapabilityResult.ok(new TerminalResult(TerminalOutcome.REQUIRES_CONFIRMATION, 0, ""));
        }
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("command", raw);
        String projectPath = client.projectPath();
        if (projectPath != null) {
            arguments.put("projectPath", projectPath);
        }
        IdeaToolCall call = client.callIdeaTool("execute_terminal_command", arguments);
        if (call.isError()) {
            String text = call.resultText() == null ? "" : call.resultText();
            if (text.toLowerCase().contains("confirm")) {
                return CapabilityResult.ok(new TerminalResult(TerminalOutcome.REQUIRES_CONFIRMATION, 0, ""));
            }
            return CapabilityResult.failed(new CapabilityFailure("Cannot execute terminal command", text));
        }
        try {
            McpJsonMapper mapper = client.jsonMapper();
            Map<?, ?> result = mapper.readValue(call.resultText(), Map.class);
            Object rawOutput = result.get("command_output");
            if (rawOutput != null && !(rawOutput instanceof String)) {
                throw new IllegalArgumentException("command_output must be a string");
            }
            String output = rawOutput == null ? "" : (String) rawOutput;
            Object timedOut = result.get("is_timed_out");
            if (timedOut != null && !(timedOut instanceof Boolean)) {
                throw new IllegalArgumentException("is_timed_out must be a boolean");
            }
            if (Boolean.TRUE.equals(timedOut)) {
                return CapabilityResult.failed(new CapabilityFailure("IDEA terminal command timed out", output));
            }
            Object rawExitCode = result.get("command_exit_code");
            if (rawExitCode == null) {
                return CapabilityResult.failed(new CapabilityFailure("IDEA terminal result is missing an exit code", output));
            }
            if (!(rawExitCode instanceof Integer) && !(rawExitCode instanceof Long)) {
                throw new IllegalArgumentException("command_exit_code must be an integer");
            }
            long exitCodeLong = ((Number) rawExitCode).longValue();
            if (exitCodeLong < Integer.MIN_VALUE || exitCodeLong > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("command_exit_code is outside the integer range");
            }
            int exitCode = (int) exitCodeLong;
            return CapabilityResult.ok(new TerminalResult(TerminalOutcome.EXECUTED, exitCode, output));
        } catch (Exception failure) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot parse terminal result", failure.getMessage()));
        }
    }

    private static boolean containsShellControlSyntax(String commandLine) {
        if (commandLine == null) {
            return false;
        }
        return commandLine.contains(";")
                || commandLine.contains("&&")
                || commandLine.contains("||")
                || commandLine.contains("|")
                || commandLine.contains("$(")
                || commandLine.contains("`")
                || commandLine.contains("\n");
    }
}
