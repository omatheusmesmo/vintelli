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

    /** Starts and initializes one agent process and opens a remote session. */
    String openSession(Consumer<Update> updates, Function<PermissionRequest, PermissionChoice> permissionHandler);

    /** Sends one prompt. Implementations may block until the turn stops. */
    String prompt(String sessionId, String text);

    /** Requests cancellation of an in-flight turn. */
    void cancel(String sessionId);

    /** Closes the remote session and its transport. */
    void close(String sessionId);

    sealed interface Update permits Message, ToolCall, TurnStopped, Failure {
    }

    /** One streamed assistant markdown fragment. */
    record Message(String text) implements Update {
        public Message {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /** One ACP tool card; an optional unified diff is rendered inline. */
    record ToolCall(String id, String title, String name, String status, String diff) implements Update {
        public ToolCall {
            id = Objects.requireNonNullElse(id, "");
            title = Objects.requireNonNullElse(title, "Tool call");
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
