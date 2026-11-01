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

    private record IdeaTerminalResult(String command_output, Integer command_exit_code,
            Boolean is_timed_out) {
    }

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
            IdeaTerminalResult result = mapper.readValue(call.resultText(), IdeaTerminalResult.class);
            String output = result.command_output() == null ? "" : result.command_output();
            int exitCode = result.command_exit_code() == null ? 0 : result.command_exit_code();
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
