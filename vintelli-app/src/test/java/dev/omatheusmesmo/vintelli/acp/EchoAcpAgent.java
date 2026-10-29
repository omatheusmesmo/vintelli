package dev.omatheusmesmo.vintelli.acp;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Minimal stdio ACP agent the application under test spawns: it answers
 * initialize, opens sessions, streams one message chunk per prompt and, for
 * prompts containing {@link #CANCEL_TRIGGER}, waits for the client's cancel
 * notification before answering. Sessions carrying MCP servers are refused
 * with a protocol error so the client's error propagation is measurable.
 */
public final class EchoAcpAgent {

    public static final String CANCEL_TRIGGER = "cancel-me";
    private static final long CANCEL_DEADLINE_MILLIS = 15_000;

    public static void main(String[] args) {
        AcpSyncAgent agent = AcpAgent.sync(new StdioAcpAgentTransport())
                .agentInfo(new AcpSchema.Implementation("vintelli-echo", "0.1.0"))
                .newSessionHandler(EchoAcpAgent::newSession)
                .promptHandler(EchoAcpAgent::prompt)
                .closeSessionHandler(request -> new AcpSchema.CloseSessionResponse())
                .build();
        agent.run();
    }

    private static AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
        if (!request.mcpServers().isEmpty()) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "the echo agent accepts no MCP servers");
        }
        return new AcpSchema.NewSessionResponse(UUID.randomUUID().toString());
    }

    private static AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
        try {
            String text = request.prompt().stream()
                    .filter(AcpSchema.TextContent.class::isInstance)
                    .map(block -> ((AcpSchema.TextContent) block).text())
                    .collect(Collectors.joining(" "));
            if (text.contains(CANCEL_TRIGGER)) {
                context.sendMessage("chunk:cancel-requested");
                long deadline = System.currentTimeMillis() + CANCEL_DEADLINE_MILLIS;
                while (!context.isCancelled() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                return context.isCancelled()
                        ? AcpSchema.PromptResponse.cancelled()
                        : AcpSchema.PromptResponse.endTurn();
            }
            context.sendMessage("chunk:" + text);
            return AcpSchema.PromptResponse.endTurn();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AcpSchema.PromptResponse.cancelled();
        }
    }
}
