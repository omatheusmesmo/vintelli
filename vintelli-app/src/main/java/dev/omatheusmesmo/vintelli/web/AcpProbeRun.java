package dev.omatheusmesmo.vintelli.web;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.omatheusmesmo.vintelli.core.acp.AcpProbePermissionPolicy;
import dev.omatheusmesmo.vintelli.core.acp.AcpTerminalRegistry;
import dev.omatheusmesmo.vintelli.core.acp.AcpWorkspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One probe run: a fresh session root with a file the agent can read, the
 * client handlers that serve it (confined file access, permission policy,
 * terminals, elicitation) and the observations they and the session updates
 * leave behind, reported as phase strings.
 */
public final class AcpProbeRun {

    static final String NOTES_CONTENT = "hello-from-workspace";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(30);

    private final Path root;
    private final AcpWorkspace workspace;
    private final AcpTerminalRegistry terminals;
    private int releasedTerminalCount;
    private final List<String> phases = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> streamedSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> updateKinds = ConcurrentHashMap.newKeySet();
    private final List<String> chunks = Collections.synchronizedList(new ArrayList<>());

    private AcpProbeRun(Path root) {
        this.root = root;
        this.workspace = new AcpWorkspace(root);
        this.terminals = new AcpTerminalRegistry(root);
    }

    static AcpProbeRun create() throws IOException {
        Path root = Files.createTempDirectory("vintelli-acp-probe-");
        Files.writeString(root.resolve("notes.txt"), NOTES_CONTENT);
        return new AcpProbeRun(root);
    }

    public Path root() {
        return root;
    }

    public List<String> phases() {
        return phases;
    }

    public List<String> chunks() {
        return chunks;
    }

    public Set<String> streamedSessions() {
        return streamedSessions;
    }

    public String readBack(String name) {
        try {
            return Files.readString(root.resolve(name));
        } catch (IOException e) {
            return "MISSING";
        }
    }

    public void close() {
        releasedTerminalCount = terminals.size();
        terminals.releaseAll();
    }

    public int releasedTerminalCount() {
        return releasedTerminalCount;
    }

    public AcpTerminalRegistry terminals() {
        return terminals;
    }

    public AcpWorkspace workspace() {
        return workspace;
    }

    public void resetUpdateKinds() {
        updateKinds.clear();
    }

    public String updateKindsObserved() {
        return String.join(",", new TreeSet<>(updateKinds));
    }

    public static AcpSchema.ClientCapabilities clientCapabilities() {
        return AcpSchema.ClientCapabilities.builder()
                .readTextFile()
                .writeTextFile()
                .terminal()
                .elicitation(AcpSchema.ElicitationCapabilities.formAndUrl())
                .build();
    }

    public AcpSchema.RequestPermissionResponse permission(AcpSchema.RequestPermissionRequest request) {
        AcpSchema.RequestPermissionResponse response = AcpProbePermissionPolicy.decide(request);
        String outcome = response.outcome() instanceof AcpSchema.PermissionSelected selected
                ? "selected:" + selected.optionId()
                : "cancelled";
        phases.add("permission:" + outcome);
        return response;
    }

    public AcpSchema.CreateElicitationResponse elicitation(AcpSchema.CreateElicitationRequest request) {
        phases.add("elicitation:" + request.mode());
        return AcpSchema.CreateElicitationRequest.MODE_FORM.equals(request.mode())
                ? AcpSchema.CreateElicitationResponse.accept(Map.of("reason", "probe"))
                : AcpSchema.CreateElicitationResponse.accept();
    }

    public void onSessionUpdate(AcpSchema.SessionNotification notification) {
        streamedSessions.add(notification.sessionId());
        updateKinds.add(kindOf(notification.update()));
        if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
                && chunk.content() instanceof AcpSchema.TextContent content) {
            chunks.add(content.text());
        }
    }

    private static String kindOf(AcpSchema.SessionUpdate update) {
        return switch (update) {
            case AcpSchema.AgentMessageChunk _ -> "agent_message_chunk";
            case AcpSchema.AgentThoughtChunk _ -> "agent_thought_chunk";
            case AcpSchema.ToolCall _ -> "tool_call";
            case AcpSchema.ToolCallUpdateNotification _ -> "tool_call_update";
            case AcpSchema.Plan _ -> "plan";
            default -> "other";
        };
    }

    private static <T> T unchecked(IoCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T call() throws Exception;
    }
}
