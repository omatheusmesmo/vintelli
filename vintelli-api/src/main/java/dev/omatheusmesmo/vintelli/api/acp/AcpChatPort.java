package dev.omatheusmesmo.vintelli.api.acp;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * TUI-facing ACP chat contract. It contains no SDK or terminal types so the
 * shell can render sessions without depending on the ACP transport module.
 */
public interface AcpChatPort {

    /** Whether the configured ACP stdio agent can be started. */
    boolean isConfigured();

    /** Available agent choices, including a reason for choices that cannot start. */
    default List<Agent> agents() {
        return isConfigured() ? List.of(new Agent("configured", "Configured agent", true, "")) : List.of();
    }

    default String openSession(String agentId, Consumer<Update> updates,
            Function<PermissionRequest, PermissionChoice> permissionHandler) {
        if (!"configured".equals(agentId)) throw new IllegalArgumentException("Unknown ACP agent: " + agentId);
        return openSession(updates, permissionHandler);
    }

    record Agent(String id, String name, boolean available, String reason) { }

    /**
     * Lists the sessions the agent stored for the project. Fails when the agent does not
     * advertise session listing.
     */
    default List<SessionSummary> listSessions(String agentId) {
        throw new UnsupportedOperationException("ACP session listing is not supported by this chat port");
    }

    /**
     * Reopens a session the agent stored. The replayed history reaches {@code updates} before this
     * returns. Fails when the agent does not advertise session loading.
     */
    default String loadSession(String agentId, String remoteSessionId, Consumer<Update> updates,
            Function<PermissionRequest, PermissionChoice> permissionHandler) {
        throw new UnsupportedOperationException("ACP session loading is not supported by this chat port");
    }

    /** A stored session: its remote id, and the title and last activity the agent reported (empty when absent). */
    record SessionSummary(String remoteId, String title, String updatedAt) {
        public SessionSummary {
            remoteId = Objects.requireNonNullElse(remoteId, "");
            title = Objects.requireNonNullElse(title, "");
            updatedAt = Objects.requireNonNullElse(updatedAt, "");
        }
    }

    /** Starts and initializes one agent process and opens a remote session. */
    String openSession(Consumer<Update> updates, Function<PermissionRequest, PermissionChoice> permissionHandler);

    /** Sends one prompt. Implementations may block until the turn stops. */
    String prompt(String sessionId, String text);

    /** Requests cancellation of an in-flight turn. */
    void cancel(String sessionId);

    /** Closes the remote session and its transport. */
    void close(String sessionId);

    sealed interface Update permits Message, Thought, Plan, UserMessage, ToolCall, TurnStopped, Failure {
    }

    /** One streamed assistant markdown fragment. */
    record Message(String text) implements Update {
        public Message {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /** One streamed fragment of the agent's reasoning, shown collapsed by default. */
    record Thought(String text) implements Update {
        public Thought {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /**
     * The agent's task list as it stands now. A newer plan replaces the previous one, so the TUI
     * keeps a single checklist per conversation.
     */
    record Plan(List<PlanEntry> entries) implements Update {
        public Plan {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** One plan entry: its text, its ACP status (pending, in_progress, completed) and its ACP priority. */
    record PlanEntry(String content, String status, String priority) {
        public PlanEntry {
            content = Objects.requireNonNullElse(content, "");
            status = Objects.requireNonNullElse(status, "");
            priority = Objects.requireNonNullElse(priority, "");
        }
    }

    /** A user prompt the agent replayed from a loaded session; it renders like a typed prompt. */
    record UserMessage(String text) implements Update {
        public UserMessage {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /**
     * One ACP tool card; an optional unified diff is rendered inline. An update
     * for a known id carries only what changed, so a blank field keeps the
     * card's earlier value. The name falls back to the ACP tool kind.
     */
    record ToolCall(String id, String title, String name, String status, String diff) implements Update {
        public ToolCall {
            id = Objects.requireNonNullElse(id, "");
            title = Objects.requireNonNullElse(title, "");
            name = Objects.requireNonNullElse(name, "");
            status = Objects.requireNonNullElse(status, "");
            diff = Objects.requireNonNullElse(diff, "");
        }
    }

    /** The ACP stop reason for a completed or cancelled turn. */
    record TurnStopped(String reason) implements Update {
        public TurnStopped {
            reason = Objects.requireNonNullElse(reason, "unknown");
        }
    }

    /** A transport or protocol failure, surfaced in the session transcript. */
    record Failure(String message) implements Update {
        public Failure {
            message = Objects.requireNonNullElse(message, "ACP agent failed");
        }
    }

    /** User-facing permission question raised synchronously by an ACP agent. */
    record PermissionRequest(String id, String toolTitle, List<PermissionOption> options) {
        public PermissionRequest {
            id = Objects.requireNonNullElse(id, "");
            toolTitle = Objects.requireNonNullElse(toolTitle, "Agent requested permission");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    record PermissionOption(String id, String label) {
        public PermissionOption {
            id = Objects.requireNonNullElse(id, "");
            label = Objects.requireNonNullElse(label, "Continue");
        }
    }

    /** Explicitly either selects one of the agent's options or declines it. */
    record PermissionChoice(String optionId, boolean cancelled) {
        public static PermissionChoice select(String optionId) {
            return new PermissionChoice(Objects.requireNonNull(optionId, "optionId"), false);
        }

        public static PermissionChoice cancel() {
            return new PermissionChoice("", true);
        }
    }
}
