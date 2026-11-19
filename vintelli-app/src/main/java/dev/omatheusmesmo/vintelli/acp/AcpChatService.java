package dev.omatheusmesmo.vintelli.acp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import dev.omatheusmesmo.vintelli.api.acp.AcpSessionIdsView;
import dev.omatheusmesmo.vintelli.core.acp.AcpTerminalRegistry;
import dev.omatheusmesmo.vintelli.core.acp.AcpWorkspace;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Production ACP session service used by the TUI chat tool window. */
@Singleton
public final class AcpChatService implements AcpChatPort, AcpSessionIdsView {

    private static final System.Logger LOG = System.getLogger(AcpChatService.class.getName());
    private static final Duration SESSION_CLOSE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CLIENT_CLOSE_TIMEOUT = Duration.ofSeconds(12);

    private final AcpAgentClientFactory agents;
    private final AcpAgentCatalog catalog;
    private final Path projectRoot;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<AcpSyncClient, AcpTerminalRegistry> clients = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private boolean shuttingDown;

    @Inject
    public AcpChatService(AcpAgentClientFactory agents, AcpAgentCatalog catalog,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String projectRoot) {
        this.agents = agents;
        this.catalog = catalog;
        this.projectRoot = Path.of(projectRoot).toAbsolutePath().normalize();
    }

    @Override
    public boolean isConfigured() {
        return catalog.agents().stream().anyMatch(Agent::available);
    }

    @Override
    public List<Agent> agents() {
        return catalog.agents();
    }

    @Override
    public List<String> activeSessionIds() {
        synchronized (lifecycleLock) {
            return sessions.keySet().stream().sorted().toList();
        }
    }

    @Override
    public String openSession(Consumer<Update> updates, Function<PermissionRequest, PermissionChoice> permissionHandler) {
        return openSession(catalog.defaultAgent(), updates, permissionHandler);
    }

    @Override
    public String openSession(String agentId, Consumer<Update> updates,
            Function<PermissionRequest, PermissionChoice> permissionHandler) {
        return openRemoteSession(agentId, updates, permissionHandler,
                client -> client.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString())).sessionId());
    }

    @Override
    public List<SessionSummary> listSessions(String agentId) {
        AcpSyncClient client = startClient(agentId, update -> { }, request -> PermissionChoice.cancel());
        try {
            client.initialize();
            requireAdvertised(agentId, client, NegotiatedCapabilities::supportsListSessions, "listing sessions");
            return storedSessions(client);
        } finally {
            closeClient(client);
        }
    }

    @Override
    public String loadSession(String agentId, String remoteSessionId, Consumer<Update> updates,
            Function<PermissionRequest, PermissionChoice> permissionHandler) {
        if (remoteSessionId == null || remoteSessionId.isBlank()) {
            throw new IllegalArgumentException("ACP session id is required");
        }
        return openRemoteSession(agentId, updates, permissionHandler, client -> {
            requireAdvertised(agentId, client, NegotiatedCapabilities::supportsLoadSession, "loading sessions");
            client.loadSession(new AcpSchema.LoadSessionRequest(remoteSessionId, projectRoot.toString(), List.of()));
            return remoteSessionId;
        });
    }

    @Override
    public String prompt(String sessionId, String text) {
        Session session = requireSession(sessionId);
        try {
            AcpSchema.PromptResponse result = session.client().prompt(AcpSchema.PromptRequest.text(session.remoteId(), text));
            String reason = result.stopReason() == null ? "unknown" : result.stopReason().value();
            session.updates().accept(new TurnStopped(reason));
            return reason;
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            session.updates().accept(new Failure(message));
            throw failure;
        }
    }

    @Override
    public void cancel(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.client().cancel(new AcpSchema.CancelNotification(session.remoteId()));
        }
    }

    @Override
    public void close(String sessionId) {
        Session session;
        synchronized (lifecycleLock) {
            session = sessions.remove(sessionId);
        }
        if (session == null) {
            return;
        }
        try {
            session.client().closeSession(new AcpSchema.CloseSessionRequest(session.remoteId()));
        } finally {
            closeClient(session.client());
        }
    }

    @PreDestroy
    void shutdown() {
        List<Session> sessionsToClose;
        Map<AcpSyncClient, AcpTerminalRegistry> clientsToClose;
        synchronized (lifecycleLock) {
            shuttingDown = true;
            sessionsToClose = List.copyOf(sessions.values());
            sessions.clear();
            clientsToClose = Map.copyOf(clients);
            clients.clear();
        }
        ExecutorService closeWorkers = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> closeRequests = sessionsToClose.stream().<Future<?>>map(session -> closeWorkers.submit(() -> {
            try {
                session.client().closeSession(new AcpSchema.CloseSessionRequest(session.remoteId()));
            } catch (RuntimeException sessionCloseFailure) {
                shutdownStepFailed(sessionCloseFailure);
            }
        })).toList();
        long deadline = System.nanoTime() + SESSION_CLOSE_TIMEOUT.toNanos();
        for (Future<?> closeRequest : closeRequests) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                closeRequest.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException sessionCloseSlow) {
                shutdownStepFailed(sessionCloseSlow);
            }
        }
        List<Future<?>> clientClosures = clientsToClose.entrySet().stream()
                .<Future<?>>map(entry -> closeWorkers.submit(() -> {
                    try {
                        entry.getKey().close();
                    } catch (RuntimeException transportCloseFailure) {
                        shutdownStepFailed(transportCloseFailure);
                    } finally {
                        entry.getValue().releaseAll();
                    }
                })).toList();
        deadline = System.nanoTime() + CLIENT_CLOSE_TIMEOUT.toNanos();
        for (Future<?> clientClosure : clientClosures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                clientClosure.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException clientCloseSlow) {
                shutdownStepFailed(clientCloseSlow);
            }
        }
        closeWorkers.shutdownNow();
    }

    private String openRemoteSession(String agentId, Consumer<Update> updates,
            Function<PermissionRequest, PermissionChoice> permissionHandler, RemoteSessionStart start) {
        AcpSyncClient client = startClient(agentId, updates, permissionHandler);
        try {
            client.initialize();
            return registerSession(client, start.open(client), updates);
        } catch (RuntimeException failure) {
            closeClient(client);
            throw failure;
        }
    }

    private AcpSyncClient startClient(String agentId, Consumer<Update> updates,
            Function<PermissionRequest, PermissionChoice> permissionHandler) {
        catalog.require(agentId);
        synchronized (lifecycleLock) {
            if (shuttingDown) {
                throw new IllegalStateException("ACP chat service is shutting down");
            }
            if (!isConfigured()) {
                throw new IllegalStateException("ACP agent is not configured");
            }
        }
        Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> permission =
                request -> answerPermission(request, permissionHandler);
        AcpWorkspace workspace = new AcpWorkspace(projectRoot);
        AcpTerminalRegistry terminals = new AcpTerminalRegistry(projectRoot);
        AcpSyncClient client = agents.chatClient(agentId, workspace, terminals,
                notification -> forwardUpdate(notification, updates), permission);
        boolean registered;
        synchronized (lifecycleLock) {
            registered = !shuttingDown;
            if (registered) {
                clients.put(client, terminals);
            }
        }
        if (!registered) {
            try {
                client.close();
            } finally {
                terminals.releaseAll();
            }
            throw new IllegalStateException("ACP chat service is shutting down");
        }
        return client;
    }

    private String registerSession(AcpSyncClient client, String remoteId, Consumer<Update> updates) {
        String id = UUID.randomUUID().toString();
        synchronized (lifecycleLock) {
            if (shuttingDown) {
                throw new IllegalStateException("ACP chat service is shutting down");
            }
            sessions.put(id, new Session(client, remoteId, updates));
        }
        return id;
    }

    private void requireAdvertised(String agentId, AcpSyncClient client, Predicate<NegotiatedCapabilities> advertised,
            String action) {
        NegotiatedCapabilities capabilities = client.getAgentCapabilities();
        if (capabilities == null || !advertised.test(capabilities)) {
            throw new IllegalStateException("agent " + agentId + " does not support " + action);
        }
    }

    private List<SessionSummary> storedSessions(AcpSyncClient client) {
        List<SessionSummary> summaries = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            AcpSchema.ListSessionsResponse page = client.listSessions(
                    new AcpSchema.ListSessionsRequest(projectRoot.toString(), cursor, null));
            page.sessions().forEach(info -> summaries.add(new SessionSummary(info.sessionId(), info.title(), info.updatedAt())));
            cursor = page.nextCursor();
        } while (cursor != null && cursors.add(cursor));
        return List.copyOf(summaries);
    }

    private static void forwardUpdate(AcpSchema.SessionNotification notification, Consumer<Update> updates) {
        AcpSchema.SessionUpdate update = notification.update();
        if (update instanceof AcpSchema.AgentMessageChunk chunk) {
            updates.accept(new Message(chunkText(chunk.content())));
        } else if (update instanceof AcpSchema.AgentThoughtChunk chunk) {
            updates.accept(new Thought(chunkText(chunk.content())));
        } else if (update instanceof AcpSchema.UserMessageChunk chunk) {
            updates.accept(new UserMessage(chunkText(chunk.content())));
        } else if (update instanceof AcpSchema.Plan plan) {
            updates.accept(new Plan(plan.entries().stream().map(AcpChatService::planEntry).toList()));
        } else if (update instanceof AcpSchema.ToolCall toolCall) {
            updates.accept(toolCall(toolCall));
        } else if (update instanceof AcpSchema.ToolCallUpdateNotification toolCall) {
            updates.accept(toolCall(toolCall));
        }
    }

    private static String chunkText(AcpSchema.ContentBlock content) {
        if (content instanceof AcpSchema.TextContent text) {
            return text.text();
        }
        return "\n" + placeholder(content) + "\n";
    }

    private static String placeholder(AcpSchema.ContentBlock content) {
        if (content instanceof AcpSchema.ImageContent) {
            return "[image]";
        }
        if (content instanceof AcpSchema.AudioContent) {
            return "[audio]";
        }
        if (content instanceof AcpSchema.ResourceLink link) {
            return resourcePlaceholder(link.uri());
        }
        if (content instanceof AcpSchema.Resource resource) {
            return resourcePlaceholder(embeddedUri(resource.resource()));
        }
        return "[content]";
    }

    private static String resourcePlaceholder(String uri) {
        return uri == null || uri.isBlank() ? "[resource]" : "[resource: " + uri + "]";
    }

    private static String embeddedUri(AcpSchema.EmbeddedResourceResource resource) {
        if (resource instanceof AcpSchema.TextResourceContents text) {
            return text.uri();
        }
        if (resource instanceof AcpSchema.BlobResourceContents blob) {
            return blob.uri();
        }
        return null;
    }

    private static PlanEntry planEntry(AcpSchema.PlanEntry entry) {
        String status = entry.status() == null ? "" : entry.status().value();
        String priority = entry.priority() == null ? "" : entry.priority().value();
        return new PlanEntry(entry.content(), status, priority);
    }

    private static AcpSchema.RequestPermissionResponse answerPermission(AcpSchema.RequestPermissionRequest request,
            Function<PermissionRequest, PermissionChoice> permissionHandler) {
        AcpSchema.RequestPermissionResponse declined =
                new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled());
        List<PermissionOption> options = request.options().stream()
                .map(option -> new PermissionOption(option.optionId(), option.name())).toList();
        PermissionChoice choice;
        try {
            choice = permissionHandler.apply(new PermissionRequest(
                    request.toolCall().toolCallId(), request.toolCall().title(), options));
        } catch (RuntimeException handlerFailure) {
            return declined;
        }
        if (choice == null || choice.cancelled() || choice.optionId().isBlank()) {
            return declined;
        }
        boolean offered = request.options().stream().anyMatch(option -> option.optionId().equals(choice.optionId()));
        return offered ? new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(choice.optionId()))
                : declined;
    }

    private static void shutdownStepFailed(Exception failure) {
        LOG.log(System.Logger.Level.DEBUG, "ACP chat shutdown step did not complete cleanly", failure);
    }

    private void closeClient(AcpSyncClient client) {
        AcpTerminalRegistry terminals;
        synchronized (lifecycleLock) {
            terminals = clients.remove(client);
        }
        if (terminals != null) {
            try {
                client.close();
            } finally {
                terminals.releaseAll();
            }
        }
    }

    private Session requireSession(String id) {
        Session session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Unknown ACP chat session");
        }
        return session;
    }

    private static ToolCall toolCall(AcpSchema.ToolCall update) {
        return toolCall(update.toolCallId(), update.title(), toolName(update.name(), update.kind()), update.status(),
                update.content());
    }

    private static ToolCall toolCall(AcpSchema.ToolCallUpdateNotification update) {
        return toolCall(update.toolCallId(), update.title(), toolName(update.name(), update.kind()), update.status(),
                update.content());
    }

    private static String toolName(String name, AcpSchema.ToolKind kind) {
        if (name != null && !name.isBlank()) return name;
        return kind == null ? "" : kind.value();
    }

    private static ToolCall toolCall(String id, String title, String name, AcpSchema.ToolCallStatus status,
            List<AcpSchema.ToolCallContent> content) {
        String diff = content == null ? "" : content.stream()
                .filter(AcpSchema.ToolCallDiff.class::isInstance)
                .map(AcpSchema.ToolCallDiff.class::cast)
                .map(AcpChatService::unifiedDiff)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining("\n"));
        return new ToolCall(id, title, name, status == null ? "" : status.value(), diff);
    }

    private static String unifiedDiff(AcpSchema.ToolCallDiff diff) {
        String path = diff.path() == null || diff.path().isBlank() ? "file" : diff.path();
        String oldText = diff.oldText() == null ? "" : diff.oldText();
        String newText = diff.newText() == null ? "" : diff.newText();
        StringBuilder text = new StringBuilder("--- a/").append(path).append("\n+++ b/").append(path).append('\n');
        appendDiffLines(text, oldText, '-');
        appendDiffLines(text, newText, '+');
        return text.toString();
    }

    private static void appendDiffLines(StringBuilder target, String source, char marker) {
        if (source.isEmpty()) {
            return;
        }
        for (String line : source.split("\\R", -1)) {
            target.append(marker).append(' ').append(line).append('\n');
        }
    }

    private record Session(AcpSyncClient client, String remoteId, Consumer<Update> updates) {
    }

    /** Opens or reopens the remote session on an initialized client and answers its remote id. */
    @FunctionalInterface
    private interface RemoteSessionStart {
        String open(AcpSyncClient client);
    }
}
