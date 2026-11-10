package dev.omatheusmesmo.vintelli.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

/**
 * AEsh counterpart of the ACP Agents tool window: sends one prompt to the
 * selected stdio agent through the same production service as the TUI. The
 * one-shot command declines permission requests because it has no chooser.
 */
@CommandDefinition(name = "prompt", description = "Sends one prompt to the configured ACP agent and reports the streamed chunks and the stop reason.",
        stopAtFirstPositional = true)
@Singleton
public final class AgentPromptCommand implements Command<CommandInvocation> {

    private final AcpChatPort chat;

    /** Required by the AEsh metadata generator; CDI uses the injected constructor. */
    public AgentPromptCommand() {
        this(null);
    }

    @Inject
    public AgentPromptCommand(AcpChatPort chat) {
        this.chat = chat;
    }

    @Option(name = "agent", description = "Agent id from agents list")
    String agent;

    @Arguments(description = "Prompt text to send")
    List<String> words;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        if (agent == null && !chat.isConfigured()) {
            invocation.println("No ACP agent is ready: use agents list and enable vintelli.acp.enabled");
            return CommandResult.FAILURE;
        }
        if (words == null || words.isEmpty()) {
            invocation.println("Nothing to send: pass the prompt text");
            return CommandResult.USAGE_ERROR;
        }
        String promptText = String.join(" ", words);
        return promptSelectedAgent(invocation, promptText);
    }

    private CommandResult promptSelectedAgent(CommandInvocation invocation, String promptText) {
        String id = null;
        List<String> chunks = Collections.synchronizedList(new ArrayList<>());
        try {
            Consumer<AcpChatPort.Update> updates = update -> {
                if (update instanceof AcpChatPort.Message message) chunks.add(message.text());
            };
            id = agent == null ? chat.openSession(updates, request -> AcpChatPort.PermissionChoice.cancel())
                    : chat.openSession(agent, updates, request -> AcpChatPort.PermissionChoice.cancel());
            String reason = chat.prompt(id, promptText);
            chunks.forEach(invocation::println);
            invocation.println("Stop reason: " + reason);
            return CommandResult.SUCCESS;
        } catch (RuntimeException failure) {
            invocation.println("Prompt failed: " + failure.getMessage());
            return CommandResult.FAILURE;
        } finally {
            if (id != null) chat.close(id);
        }
    }
}
