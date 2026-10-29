package dev.omatheusmesmo.vintelli.acp;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.Command;
import com.agentclientprotocol.sdk.agent.CommandResult;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Minimal stdio ACP agent the application under test spawns. Its behavior is
 * driven by the prompt text and by its arguments: {@code --protocol-version=N}
 * is the version it answers to initialize, and {@code --mcp=http,sse} lists the
 * MCP transports it advertises (default {@code http}). A session asking for an
 * MCP transport that is not advertised is refused with a protocol error.
 *
 * <p>Prompt directives: {@code echo:} streams the text back, {@code kinds}
 * streams every session update kind the client must parse, {@code permit}
 * asks for permission, {@code fs:<root>} reads, writes and probes a path outside
 * the root, {@code terminal} runs a command, {@code elicit} asks the user for a
 * URL and a form, and {@code cancel-me} waits for a cancel notification.
 */
public final class EchoAcpAgent {

    public static final String CANCEL_TRIGGER = "cancel-me";
    private static final long CANCEL_DEADLINE_MILLIS = 15_000;

    private static int protocolVersion = 1;
    private static boolean mcpHttp = true;
    private static boolean mcpSse;

    public static void main(String[] args) {
        configure(args);
        AcpSyncAgent agent = AcpAgent.sync(new StdioAcpAgentTransport())
                .agentInfo(new AcpSchema.Implementation("vintelli-echo", "0.1.0"))
                .initializeHandler(request -> new AcpSchema.InitializeResponse(protocolVersion, capabilities(), List.of()))
                .newSessionHandler(EchoAcpAgent::newSession)
                .promptHandler(EchoAcpAgent::prompt)
                .closeSessionHandler(request -> new AcpSchema.CloseSessionResponse())
                .build();
        agent.run();
    }

    static void configure(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--protocol-version=")) {
                protocolVersion = Integer.parseInt(arg.substring("--protocol-version=".length()));
            } else if (arg.startsWith("--mcp=")) {
                List<String> transports = List.of(arg.substring("--mcp=".length()).split(","));
                mcpHttp = transports.contains("http");
                mcpSse = transports.contains("sse");
            }
        }
    }

    private static AcpSchema.AgentCapabilities capabilities() {
        AcpSchema.SessionCapabilities sessions = new AcpSchema.SessionCapabilities(null, Map.of(), null, null, null, null);
        return AcpSchema.AgentCapabilities.builder()
                .sessionCapabilities(sessions)
                .mcpCapabilities(new AcpSchema.McpCapabilities(mcpHttp, mcpSse))
                .build();
    }

    private static AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
        boolean unadvertised = request.mcpServers().stream().anyMatch(server ->
                (server instanceof AcpSchema.McpServerHttp && !mcpHttp)
                        || (server instanceof AcpSchema.McpServerSse && !mcpSse));
        if (unadvertised) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "the echo agent did not advertise that MCP transport");
        }
        return new AcpSchema.NewSessionResponse(UUID.randomUUID().toString());
    }

    private static AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
        String text = request.prompt().stream()
                .filter(AcpSchema.TextContent.class::isInstance)
                .map(block -> ((AcpSchema.TextContent) block).text())
                .collect(Collectors.joining(" "));
        try {
            if (text.contains(CANCEL_TRIGGER)) {
                return cancellable(context);
            }
            if (text.contains("kinds")) {
                streamEveryKind(context);
            } else if (text.contains("permit")) {
                context.sendMessage("result:permission=" + context.askPermission("write notes.txt", AcpSchema.ToolKind.EDIT));
            } else if (text.contains("fs:")) {
                exerciseFiles(context, text.substring(text.indexOf("fs:") + "fs:".length()).trim());
            } else if (text.contains("terminal")) {
                exerciseTerminal(context);
            } else if (text.contains("elicit")) {
                exerciseElicitation(context);
            } else {
                context.sendMessage("chunk:" + text);
            }
            return AcpSchema.PromptResponse.endTurn();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AcpSchema.PromptResponse.cancelled();
        }
    }

    private static AcpSchema.PromptResponse cancellable(SyncPromptContext context) throws InterruptedException {
        context.sendMessage("chunk:cancel-requested");
        long deadline = System.currentTimeMillis() + CANCEL_DEADLINE_MILLIS;
        while (!context.isCancelled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        return context.isCancelled() ? AcpSchema.PromptResponse.cancelled() : AcpSchema.PromptResponse.endTurn();
    }

    private static void streamEveryKind(SyncPromptContext context) {
        context.sendThought("thinking");
        context.sendMessage("kinds");
        context.sendSessionUpdate(new AcpSchema.ToolCall("tool_call", "tool-1", "Run tool", "run_tool", AcpSchema.ToolKind.EXECUTE,
                AcpSchema.ToolCallStatus.PENDING, List.of(), List.of(), null, null, Map.of()));
        context.sendSessionUpdate(new AcpSchema.ToolCallUpdateNotification("tool_call_update", "tool-1", "Run tool", "run_tool",
                AcpSchema.ToolKind.EXECUTE, AcpSchema.ToolCallStatus.COMPLETED, List.of(), List.of(), null, null, Map.of()));
        context.sendSessionUpdate(new AcpSchema.Plan(List.of(
                new AcpSchema.PlanEntry("read the notes", AcpSchema.PlanEntryPriority.HIGH, AcpSchema.PlanEntryStatus.COMPLETED))));
    }

    private static void exerciseFiles(SyncPromptContext context, String root) {
        String read = context.readFile(Path.of(root, "notes.txt").toString());
        context.sendMessage("result:fs-read=" + read);
        try {
            context.readFile(Path.of(root, "..", "secret.txt").toString());
            context.sendMessage("result:fs-outside=ALLOWED");
        } catch (RuntimeException refused) {
            context.sendMessage("result:fs-outside=DENIED");
        }
        context.writeFile(Path.of(root, "out.txt").toString(), "written-by-agent");
        context.sendMessage("result:fs-write=OK");
    }

    private static void exerciseTerminal(SyncPromptContext context) {
        String java = ProcessHandle.current().info().command().orElseThrow();
        CommandResult result = context.execute(Command.of(java, "-version"));
        context.sendMessage("result:terminal=exit:" + result.exitCode() + ",version:" + result.output().contains("version"));
    }

    private static void exerciseElicitation(SyncPromptContext context) {
        String sessionId = context.getSessionId();
        AcpSchema.CreateElicitationResponse url = context.client().createElicitation(
                AcpSchema.CreateElicitationRequest.url(sessionId, "elicit-url", "Authorize access", "https://example.invalid/authorize"));
        context.sendMessage("result:elicitation-url=" + url.action());
        AcpSchema.ElicitationSchema schema = new AcpSchema.ElicitationSchema(
                Map.of("reason", AcpSchema.StringPropertySchema.text("Reason")), List.of("reason"));
        AcpSchema.CreateElicitationResponse form = context.client().createElicitation(
                AcpSchema.CreateElicitationRequest.form(sessionId, "Why do you need access?", schema));
        context.sendMessage("result:elicitation-form=" + form.action() + ":" + form.content().get("reason"));
    }
}
