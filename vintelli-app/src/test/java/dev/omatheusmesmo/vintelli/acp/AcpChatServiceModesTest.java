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
@WithTestResource(EchoAcpAgentModesResource.class)
class AcpChatServiceModesTest {

    @Inject
    AcpChatService chat;

    @Test
    void modesOpenTheSessionCommandsArriveWithTheTurnAndSetModeIsConfirmedByTheTwin() {
        List<AcpChatPort.Update> updates = new CopyOnWriteArrayList<>();
        String session = chat.openSession(updates::add, request -> AcpChatPort.PermissionChoice.cancel());
        try {
            assertEquals(List.of(
                    new AcpChatPort.AvailableModes(List.of(
                            new AcpChatPort.Mode(EchoAcpAgent.PLAN_MODE, "Plan", "Plans the change without editing files"),
                            new AcpChatPort.Mode(EchoAcpAgent.EDIT_MODE, "Edit", "Edits files"))),
                    new AcpChatPort.CurrentMode(EchoAcpAgent.PLAN_MODE)),
                    updates);

            updates.clear();
            chat.prompt(session, "commands");
            assertEquals(List.of(
                    new AcpChatPort.AvailableCommands(List.of(
                            new AcpChatPort.Command("plan", "Draft a plan", "task to plan"),
                            new AcpChatPort.Command("review", "Review the diff", ""))),
                    new AcpChatPort.CurrentMode(EchoAcpAgent.PLAN_MODE),
                    new AcpChatPort.TurnStopped("end_turn")),
                    updates);

            updates.clear();
            chat.setMode(session, EchoAcpAgent.EDIT_MODE);
            assertEquals(List.of(new AcpChatPort.CurrentMode(EchoAcpAgent.EDIT_MODE)), updates);
        } finally {
            chat.close(session);
        }
    }
}
