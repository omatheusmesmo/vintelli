package dev.omatheusmesmo.vintelli.cli;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage: streamed assistant chunks are printed as one joined reply. */
class AgentPromptCommandTest {

    /** Chat port that replays a reply split across several streamed Message chunks. */
    private static final class ChunkedChat implements AcpChatPort {
        private final List<String> replyChunks;

        ChunkedChat(List<String> replyChunks) {
            this.replyChunks = replyChunks;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String openSession(Consumer<Update> updates,
                Function<PermissionRequest, PermissionChoice> permissionHandler) {
            replyChunks.forEach(text -> updates.accept(new Message(text)));
            return "session-1";
        }

        @Override
        public String prompt(String sessionId, String text) {
            return "end_turn";
        }

        @Override
        public void cancel(String sessionId) {
        }

        @Override
        public void close(String sessionId) {
        }
    }

    /** CommandInvocation that records everything printed to the CLI. */
    private static final class CapturingInvocation {
        final List<String> lines = new ArrayList<>();

        CommandInvocation asInvocation() {
            return (CommandInvocation) Proxy.newProxyInstance(
                    CommandInvocation.class.getClassLoader(),
                    new Class<?>[]{CommandInvocation.class},
                    (proxy, method, args) -> {
                        if ("println".equals(method.getName()) && args != null && args.length == 1) {
                            lines.add(String.valueOf(args[0]));
                            return proxy;
                        }
                        if ("toString".equals(method.getName())) return "capturing-invocation";
                        return null;
                    });
        }
    }

    @Test
    void joinsStreamedChunksIntoASingleReplyLine() throws Exception {
        AgentPromptCommand command = new AgentPromptCommand(
                new ChunkedChat(List.of("Hel", "lo wor", "ld")));
        command.agent = "configured";
        command.words = List.of("hi");

        CapturingInvocation out = new CapturingInvocation();
        CommandResult result = command.execute(out.asInvocation());

        assertEquals(CommandResult.SUCCESS, result);
        assertTrue(out.lines.contains("Hello world"),
                "expected a single joined reply line, got " + out.lines);
        assertEquals("Stop reason: end_turn", out.lines.get(out.lines.size() - 1));
    }

    @Test
    void emptyReplyPrintsNoLineBeforeStopReason() throws Exception {
        AgentPromptCommand command = new AgentPromptCommand(new ChunkedChat(List.of()));
        command.agent = "configured";
        command.words = List.of("hi");

        CapturingInvocation out = new CapturingInvocation();
        CommandResult result = command.execute(out.asInvocation());

        assertEquals(CommandResult.SUCCESS, result);
        assertEquals(List.of("Stop reason: end_turn"), out.lines);
    }
}
