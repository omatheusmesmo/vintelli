package dev.omatheusmesmo.vintelli.acp;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Minimal stdio ACP agent the application under test spawns. Its behavior is
 * driven by the prompt text and by its arguments: {@code --protocol-version=N}
 * is the version it answers to initialize, and {@code --mcp=http,sse} lists the
 * MCP transports it advertises (default {@code http}). A session asking for an
 * MCP transport that is not advertised is refused with a protocol error.
 * {@code --sessions} advertises session listing and loading, and then lists and
 * replays the one stored session {@link #STORED_SESSION_ID}. {@code --modes} offers
 * the plan and edit session modes when a session opens or loads, and confirms every
 * {@code session/set_mode} with a current mode update.
 *
 * <p>Prompt directives: {@code echo:} streams the text back, {@code kinds}
 * streams agent thought, agent message, tool call, tool call update and plan updates, {@code permit}
 * asks for permission, {@code fs:<root>} reads, writes and probes a path outside
 * the root, {@code terminal} runs a command, {@code hold-terminal} starts one that keeps running,
 * {@code elicit} asks the user for a URL and a form, {@code rich} streams thought chunks, a user
 * prompt, image, resource and plan updates, {@code commands} streams an available commands update
 * and the current mode, and {@code cancel-me} waits for a cancel notification.
 */
public final class EchoAcpAgent {

    public static final String CANCEL_TRIGGER = "cancel-me";
    public static final String STORED_SESSION_ID = "stored-session-1";
    static final String STORED_SESSION_TITLE = "Earlier refactor";
    static final String STORED_SESSION_UPDATED_AT = "2026-01-02T03:04:05Z";
    static final String PLAN_MODE = "plan";
    static final String EDIT_MODE = "edit";
    private static final long CANCEL_DEADLINE_MILLIS = 15_000;

    private static int protocolVersion = 1;
    private static boolean mcpHttp = true;
    private static boolean mcpSse;
    private static boolean sessionsAdvertised;
    private static boolean modesAdvertised;
    private static String currentMode = PLAN_MODE;
    private static Path lifecycleLog;
    private static AcpSyncAgent running;

    public static void main(String[] args) {
        configure(args);
        if (lifecycleLog != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> recordLifecycle("agent-exit")));
        }
        AcpSyncAgent agent = AcpAgent.sync(new StdioAcpAgentTransport())
                .agentInfo(new AcpSchema.Implementation("vintelli-echo", "0.1.0"))
                .initializeHandler(request -> new AcpSchema.InitializeResponse(protocolVersion, capabilities(), List.of()))
                .newSessionHandler(EchoAcpAgent::newSession)
                .promptHandler(EchoAcpAgent::prompt)
                .setSessionModeHandler(EchoAcpAgent::setSessionMode)
                .listSessionsHandler(request -> new AcpSchema.ListSessionsResponse(List.of(
                        new AcpSchema.SessionInfo(STORED_SESSION_ID, request.cwd(), STORED_SESSION_TITLE,
                                STORED_SESSION_UPDATED_AT, null, null))))
                .loadSessionHandler(EchoAcpAgent::loadSession)
                .closeSessionHandler(request -> {
                    recordLifecycle("session-close");
                    return new AcpSchema.CloseSessionResponse();
                })
                .build();
        running = agent;
        agent.run();
    }

    private static AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest request) {
        if (!STORED_SESSION_ID.equals(request.sessionId())) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "the echo agent has no stored session " + request.sessionId());
        }
        running.sendSessionUpdate(request.sessionId(),
                new AcpSchema.UserMessageChunk(new AcpSchema.TextContent("replayed-user:" + request.sessionId())));
        running.sendSessionUpdate(request.sessionId(),
                new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("replayed:" + request.sessionId())));
        return new AcpSchema.LoadSessionResponse(modeState());
    }

    private static AcpSchema.SetSessionModeResponse setSessionMode(AcpSchema.SetSessionModeRequest request) {
        currentMode = request.modeId();
        running.sendSessionUpdate(request.sessionId(), new AcpSchema.CurrentModeUpdate(currentMode));
        return new AcpSchema.SetSessionModeResponse();
    }

    private static AcpSchema.SessionModeState modeState() {
        if (!modesAdvertised) {
            return null;
        }
        return new AcpSchema.SessionModeState(currentMode, List.of(
                new AcpSchema.SessionMode(PLAN_MODE, "Plan", "Plans the change without editing files"),
                new AcpSchema.SessionMode(EDIT_MODE, "Edit", "Edits files")));
    }

    static void configure(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--protocol-version=")) {
                protocolVersion = Integer.parseInt(arg.substring("--protocol-version=".length()));
            } else if (arg.startsWith("--mcp=")) {
                List<String> transports = List.of(arg.substring("--mcp=".length()).split(","));
                mcpHttp = transports.contains("http");
                mcpSse = transports.contains("sse");
            } else if (arg.equals("--sessions")) {
                sessionsAdvertised = true;
            } else if (arg.equals("--modes")) {
                modesAdvertised = true;
            } else if (arg.startsWith("--lifecycle-log=")) {
                lifecycleLog = Path.of(arg.substring("--lifecycle-log=".length()));
            }
        }
    }

    private static void recordLifecycle(String event) {
        if (lifecycleLog == null) return;
        try {
            Files.writeString(lifecycleLog, event + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException lifecycleLogUnavailable) {
            return;
        }
    }

    private static AcpSchema.AgentCapabilities capabilities() {
        AcpSchema.SessionCapabilities sessions = new AcpSchema.SessionCapabilities(
                sessionsAdvertised ? Map.of() : null, Map.of(), null, null, null, null);
        return AcpSchema.AgentCapabilities.builder()
                .loadSession(sessionsAdvertised)
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
        currentMode = PLAN_MODE;
        return new AcpSchema.NewSessionResponse(UUID.randomUUID().toString(), modeState());
    }

    private static AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
        String text = request.prompt().stream()
                .filter(AcpSchema.TextContent.class::isInstance)
                .map(block -> ((AcpSchema.TextContent) block).text())
                .collect(Collectors.joining(" "));
        if (text.contains(CANCEL_TRIGGER)) {
            return cancellable(context);
        }
        if (text.contains("sparse-tool")) {
            streamSparseToolCall(context);
        } else if (text.contains("kinds")) {
            streamEveryKind(context);
        } else if (text.contains("rich")) {
            streamRichContent(context);
        } else if (text.contains("edit")) {
            streamEdit(context);
        } else if (text.contains("permit")) {
            context.sendMessage("result:permission=" + context.askPermission("write notes.txt", AcpSchema.ToolKind.EDIT));
        } else if (text.contains("fs:")) {
            exerciseFiles(context, text.substring(text.indexOf("fs:") + "fs:".length()).trim());
        } else if (text.contains("hold-terminal")) {
            holdTerminal(context);
        } else if (text.contains("terminal")) {
            exerciseTerminal(context);
        } else if (text.contains("elicit")) {
            exerciseElicitation(context);
        } else if (text.contains("commands")) {
            streamCommands(context);
        } else {
            context.sendMessage("chunk:" + text);
        }
        return AcpSchema.PromptResponse.endTurn();
    }

    private static AcpSchema.PromptResponse cancellable(SyncPromptContext context) {
        context.sendMessage("chunk:cancel-requested");
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        context.onCancel(() -> cancelled.complete(true));
        if (context.isCancelled()) {
            cancelled.complete(true);
        }
        boolean wasCancelled = cancelled.completeOnTimeout(false, CANCEL_DEADLINE_MILLIS, TimeUnit.MILLISECONDS).join();
        return wasCancelled ? AcpSchema.PromptResponse.cancelled() : AcpSchema.PromptResponse.endTurn();
    }

    private static void streamSparseToolCall(SyncPromptContext context) {
        context.sendSessionUpdate(new AcpSchema.ToolCall("tool_call", "tool-read", "Read notes.txt", null,
                AcpSchema.ToolKind.READ, AcpSchema.ToolCallStatus.PENDING, List.of(), List.of(), null, null, Map.of()));
        context.sendSessionUpdate(new AcpSchema.ToolCallUpdateNotification("tool_call_update", "tool-read", null, null,
                null, AcpSchema.ToolCallStatus.COMPLETED, null, null, null, null, Map.of()));
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

    private static void streamCommands(SyncPromptContext context) {
        context.sendSessionUpdate(new AcpSchema.AvailableCommandsUpdate(List.of(
                new AcpSchema.AvailableCommand("plan", "Draft a plan", new AcpSchema.AvailableCommandInput("task to plan")),
                new AcpSchema.AvailableCommand("review", "Review the diff", null))));
        context.sendSessionUpdate(new AcpSchema.CurrentModeUpdate(currentMode));
    }

    private static void streamRichContent(SyncPromptContext context) {
        context.sendSessionUpdate(new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent("first ")));
        context.sendSessionUpdate(new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent("second")));
        context.sendSessionUpdate(new AcpSchema.UserMessageChunk(new AcpSchema.TextContent("replayed prompt")));
        context.sendSessionUpdate(new AcpSchema.AgentMessageChunk(
                new AcpSchema.ImageContent("image", "aW1n", "image/png", null, null, Map.of())));
        context.sendSessionUpdate(new AcpSchema.AgentMessageChunk(new AcpSchema.ResourceLink(
                "resource_link", "Main", "file:///src/Main.java", null, null, null, null, null, Map.of())));
        context.sendSessionUpdate(new AcpSchema.AgentMessageChunk(new AcpSchema.Resource("resource",
                new AcpSchema.TextResourceContents("notes", "file:///notes.txt", "text/plain"), null, Map.of())));
        context.sendSessionUpdate(new AcpSchema.Plan(List.of(
                new AcpSchema.PlanEntry("read the notes", AcpSchema.PlanEntryPriority.HIGH, AcpSchema.PlanEntryStatus.PENDING),
                new AcpSchema.PlanEntry("edit the code", AcpSchema.PlanEntryPriority.MEDIUM, AcpSchema.PlanEntryStatus.IN_PROGRESS))));
        context.sendSessionUpdate(new AcpSchema.Plan(List.of(
                new AcpSchema.PlanEntry("read the notes", AcpSchema.PlanEntryPriority.HIGH, AcpSchema.PlanEntryStatus.COMPLETED),
                new AcpSchema.PlanEntry("edit the code", AcpSchema.PlanEntryPriority.MEDIUM, AcpSchema.PlanEntryStatus.IN_PROGRESS))));
    }

    private static void streamEdit(SyncPromptContext context) {
        AcpSchema.ToolCallDiff diff = new AcpSchema.ToolCallDiff("diff", "src/Main.java", "old line", "new line");
        context.sendSessionUpdate(new AcpSchema.ToolCall("tool_call", "Edit source", "tool-edit", "edit_file",
                AcpSchema.ToolKind.EDIT, AcpSchema.ToolCallStatus.PENDING, List.of(diff), List.of(), null, null, Map.of()));
        context.sendSessionUpdate(new AcpSchema.ToolCallUpdateNotification("tool_call_update", "tool-edit", "Edit source",
                "edit_file", AcpSchema.ToolKind.EDIT, AcpSchema.ToolCallStatus.COMPLETED,
                List.of(diff), List.of(), null, null, Map.of()));
        context.sendMessage("edit streamed");
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
        AcpSchema.CreateTerminalResponse created = context.client().createTerminal(
                new AcpSchema.CreateTerminalRequest(context.getSessionId(), java, List.of("-version"), null, List.of(), null));
        String terminalId = created.terminalId();
        AcpSchema.WaitForTerminalExitResponse exit = context.client().waitForTerminalExit(
                new AcpSchema.WaitForTerminalExitRequest(context.getSessionId(), terminalId));
        AcpSchema.TerminalOutputResponse output = context.client().getTerminalOutput(
                new AcpSchema.TerminalOutputRequest(context.getSessionId(), terminalId));
        context.sendMessage("result:terminal=exit:" + exit.exitCode() + ",version:" + output.output().contains("version"));
    }

    private static void holdTerminal(SyncPromptContext context) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command = windows ? "cmd" : "sh";
        List<String> args = windows
                ? List.of("/c", "ping -n 61 127.0.0.1 > nul")
                : List.of("-c", "sleep 60");
        context.client().createTerminal(
                new AcpSchema.CreateTerminalRequest(context.getSessionId(), command, args, null, List.of(), null));
        context.sendMessage("result:terminal-held=true");
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
