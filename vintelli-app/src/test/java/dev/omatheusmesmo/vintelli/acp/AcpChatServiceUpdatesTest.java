package dev.omatheusmesmo.vintelli.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(EchoAcpAgentResource.class)
class AcpChatServiceUpdatesTest {

    @Inject
    AcpChatService chat;

    @Test
    void anAgentThatOffersNoSessionModesOpensWithoutModeUpdates() {
        List<AcpChatPort.Update> updates = new CopyOnWriteArrayList<>();
        String session = chat.openSession(updates::add, request -> AcpChatPort.PermissionChoice.cancel());
        try {
            assertEquals(List.of(), updates);
        } finally {
            chat.close(session);
        }
    }

    @Test
    void thoughtsUserPromptsPlansAndNonTextBlocksMapToPortRecords() {
        List<AcpChatPort.Update> updates = new CopyOnWriteArrayList<>();
        String session = chat.openSession(updates::add, request -> AcpChatPort.PermissionChoice.cancel());
        try {
            chat.prompt(session, "rich");

            assertEquals(List.of(
                    new AcpChatPort.Thought("first "),
                    new AcpChatPort.Thought("second"),
                    new AcpChatPort.UserMessage("replayed prompt"),
                    new AcpChatPort.Message("\n[image]\n"),
                    new AcpChatPort.Message("\n[resource: file:///src/Main.java]\n"),
                    new AcpChatPort.Message("\n[resource: file:///notes.txt]\n"),
                    new AcpChatPort.Plan(List.of(
                            new AcpChatPort.PlanEntry("read the notes", "pending", "high"),
                            new AcpChatPort.PlanEntry("edit the code", "in_progress", "medium"))),
                    new AcpChatPort.Plan(List.of(
                            new AcpChatPort.PlanEntry("read the notes", "completed", "high"),
                            new AcpChatPort.PlanEntry("edit the code", "in_progress", "medium"))),
                    new AcpChatPort.TurnStopped("end_turn")),
                    updates);
        } finally {
            chat.close(session);
        }
    }

    @Test
    void aToolCallWithoutANameShowsItsKindAndAnUpdateSendsOnlyWhatChanged() {
        List<AcpChatPort.Update> updates = new CopyOnWriteArrayList<>();
        String session = chat.openSession(updates::add, request -> AcpChatPort.PermissionChoice.cancel());
        try {
            chat.prompt(session, "sparse-tool");

            assertEquals(List.of(
                    new AcpChatPort.ToolCall("tool-read", "Read notes.txt", "read", "pending", ""),
                    new AcpChatPort.ToolCall("tool-read", "", "", "completed", ""),
                    new AcpChatPort.TurnStopped("end_turn")),
                    updates);
        } finally {
            chat.close(session);
        }
    }
}
