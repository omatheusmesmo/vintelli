package dev.omatheusmesmo.vintelli.acp;

import java.nio.file.Path;
import java.time.Duration;
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
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import dev.omatheusmesmo.vintelli.core.acp.AcpAgentGate;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Production ACP session service used by the TUI chat tool window. */
@Singleton
public final class AcpChatService implements AcpChatPort {

    private static final System.Logger LOG = System.getLogger(AcpChatService.class.getName());
    private static final Duration SESSION_CLOSE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CLIENT_CLOSE_TIMEOUT = Duration.ofSeconds(12);

    private final AcpAgentClientFactory agents;
    private final Path projectRoot;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Set<AcpSyncClient> clients = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private boolean shuttingDown;

    @Inject
    public AcpChatService(AcpAgentClientFactory agents,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String projectRoot) {
        this.agents = agents;
        this.projectRoot = Path.of(projectRoot).toAbsolutePath().normalize();
    }

    @Override
    public boolean isConfigured() {
        return agents.isConfigured();
    }

    @Override
    public String openSession(Consumer<Update> updates, Function<PermissionRequest, PermissionChoice> permissionHandler) {
        synchronized (lifecycleLock) {
            if (shuttingDown) {
                throw new IllegalStateException("ACP chat service is shutting down");
            }
            if (!isConfigured()) {
                throw new IllegalStateException("ACP agent is not configured");
            }
        }
        String id = UUID.randomUUID().toString();
        Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> permission =
                request -> answerPermission(request, permissionHandler);
        AcpSyncClient client = agents.chatClient(notification -> {
            AcpSchema.SessionUpdate update = notification.update();
            if (update instanceof AcpSchema.AgentMessageChunk chunk
                    && chunk.content() instanceof AcpSchema.TextContent content) {
                updates.accept(new Message(content.text()));
            } else if (update instanceof AcpSchema.ToolCall toolCall) {
                updates.accept(toolCall(toolCall));
            } else if (update instanceof AcpSchema.ToolCallUpdateNotification toolCall) {
                updates.accept(toolCall(toolCall));
            }
        }, permission);
        boolean registered;
        synchronized (lifecycleLock) {
            registered = !shuttingDown;
            if (registered) {
                clients.add(client);
            }
        }
        if (!registered) {
            client.close();
            throw new IllegalStateException("ACP chat service is shutting down");
        }
        try {
            AcpAgentGate.requireSupportedVersion(client.initialize());
            AcpSchema.NewSessionResponse opened = client.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString()));
            synchronized (lifecycleLock) {
                if (shuttingDown) {
                    throw new IllegalStateException("ACP chat service is shutting down");
                }
                sessions.put(id, new Session(client, opened.sessionId(), updates));
            }
            return id;
        } catch (RuntimeException failure) {
            closeClient(client);
            throw failure;
        }
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
        List<AcpSyncClient> clientsToClose;
        synchronized (lifecycleLock) {
            shuttingDown = true;
            sessionsToClose = List.copyOf(sessions.values());
            sessions.clear();
            clientsToClose = List.copyOf(clients);
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
        List<Future<?>> clientClosures = clientsToClose.stream().<Future<?>>map(client -> closeWorkers.submit(() -> {
            try {
                client.close();
            } catch (RuntimeException transportCloseFailure) {
                shutdownStepFailed(transportCloseFailure);
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
        boolean owned;
        synchronized (lifecycleLock) {
            owned = clients.remove(client);
        }
        if (owned) {
            client.close();
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
        return toolCall(update.toolCallId(), update.title(), update.name(), update.status(), update.content());
    }

    private static ToolCall toolCall(AcpSchema.ToolCallUpdateNotification update) {
        return toolCall(update.toolCallId(), update.title(), update.name(), update.status(), update.content());
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
}
