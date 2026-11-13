package dev.omatheusmesmo.vintelli.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(AcpSessionsAgentResource.class)
class AcpChatServiceSessionsTest {

    private static final AcpChatPort.SessionSummary STORED_SESSION = new AcpChatPort.SessionSummary(
            EchoAcpAgent.STORED_SESSION_ID, EchoAcpAgent.STORED_SESSION_TITLE, EchoAcpAgent.STORED_SESSION_UPDATED_AT);

    @Inject
    AcpChatService chat;

    @Test
    void listsTheSessionsTheAgentStored() {
        assertEquals(List.of(STORED_SESSION), chat.listSessions("sessions"));
    }

    @Test
    void loadRegistersTheStoredSessionAndStreamsItsReplayedHistory() {
        List<AcpChatPort.Update> updates = new CopyOnWriteArrayList<>();
        int activeBefore = chat.activeSessionIds().size();
        String id = chat.loadSession("sessions", STORED_SESSION.remoteId(), updates::add,
                request -> AcpChatPort.PermissionChoice.cancel());
        try {
            assertTrue(chat.activeSessionIds().contains(id));
            assertEquals(activeBefore + 1, chat.activeSessionIds().size());
            assertEquals(List.of(new AcpChatPort.Message("replayed:" + STORED_SESSION.remoteId())), updates);

            chat.prompt(id, "after-load");

            assertTrue(updates.contains(new AcpChatPort.Message("chunk:after-load")), updates.toString());
        } finally {
            chat.close(id);
        }
    }

    @Test
    void loadOfAnUnknownSessionFailsAndRegistersNothing() {
        int activeBefore = chat.activeSessionIds().size();

        assertThrows(RuntimeException.class, () -> chat.loadSession("sessions", "unknown-session",
                update -> { }, request -> AcpChatPort.PermissionChoice.cancel()));

        assertEquals(activeBefore, chat.activeSessionIds().size());
    }

    @Test
    void listRefusesAnAgentThatDoesNotAdvertiseSessionListing() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> chat.listSessions("plain"));

        assertEquals("agent plain does not support listing sessions", refused.getMessage());
    }

    @Test
    void loadRefusesAnAgentThatDoesNotAdvertiseLoadingAndRegistersNothing() {
        int activeBefore = chat.activeSessionIds().size();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> chat.loadSession("plain",
                STORED_SESSION.remoteId(), update -> { }, request -> AcpChatPort.PermissionChoice.cancel()));

        assertEquals("agent plain does not support loading sessions", refused.getMessage());
        assertEquals(activeBefore, chat.activeSessionIds().size());
    }
}
