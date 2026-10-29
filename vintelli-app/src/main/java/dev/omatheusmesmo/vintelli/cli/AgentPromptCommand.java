package dev.omatheusmesmo.vintelli.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.omatheusmesmo.vintelli.acp.AcpAgentClientFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Arguments;

/**
 * AEsh counterpart of the ACP Agents tool window: sends one prompt to the
 * configured stdio agent over the ACP SDK client, exactly the surface the
 * HTTP probe endpoint drives. The client construction and the agent command
 * configuration live in {@link AcpAgentClientFactory}, so this command holds
 * none of it.
 */
@CommandDefinition(name = "prompt", description = "Sends one prompt to the configured ACP agent and reports the streamed chunks and the stop reason.",
        stopAtFirstPositional = true)
@Singleton
public final class AgentPromptCommand implements Command<CommandInvocation> {

    @Inject
    AcpAgentClientFactory agents;

    @Arguments(description = "Prompt text to send")
    List<String> words;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        if (!agents.isConfigured()) {
            invocation.println("ACP agents are not configured: set vintelli.acp.probe.agent-command"
                    + " and vintelli.acp.probe.agent-args, and switch vintelli.acp.probe.enabled on");
            return CommandResult.FAILURE;
        }
        if (words == null || words.isEmpty()) {
            invocation.println("Nothing to send: pass the prompt text");
            return CommandResult.USAGE_ERROR;
        }
        String promptText = String.join(" ", words);
        Set<String> streamedSessions = ConcurrentHashMap.newKeySet();
        List<String> chunkTexts = Collections.synchronizedList(new ArrayList<>());
        AcpSyncClient client = agents.client(streamedSessions, chunkTexts);
        try {
            client.initialize();
            AcpSchema.NewSessionResponse session = client.newSession(new AcpSchema.NewSessionRequest("."));
            AcpSchema.PromptResponse stopped = client.prompt(AcpSchema.PromptRequest.text(session.sessionId(), promptText));
            for (String chunk : chunkTexts) {
                invocation.println(chunk);
            }
            invocation.println("Stop reason: " + stopped.stopReason());
            return CommandResult.SUCCESS;
        } catch (Exception failure) {
            invocation.println("Prompt failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return CommandResult.FAILURE;
        } finally {
            client.close();
        }
    }
}
