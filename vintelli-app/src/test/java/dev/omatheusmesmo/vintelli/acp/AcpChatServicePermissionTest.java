package dev.omatheusmesmo.vintelli.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(EchoAcpAgentResource.class)
class AcpChatServicePermissionTest {

    @Inject
    AcpChatService chat;

    @Test
    void permissionHandlerFailureDeclinesTheRequestInsteadOfFailingTheTurn() {
        List<String> messages = new CopyOnWriteArrayList<>();
        String session = chat.openSession(update -> {
            if (update instanceof AcpChatPort.Message message) {
                messages.add(message.text());
            }
        }, request -> {
            throw new IllegalStateException("chooser crashed");
        });
        try {
            chat.prompt(session, "permit");
            assertTrue(messages.contains("result:permission=false"), messages.toString());
        } finally {
            chat.close(session);
        }
    }

    @Test
    void anOptionThatWasNeverOfferedIsDeclined() {
        List<String> messages = new CopyOnWriteArrayList<>();
        String session = chat.openSession(update -> {
            if (update instanceof AcpChatPort.Message message) {
                messages.add(message.text());
            }
        }, request -> AcpChatPort.PermissionChoice.select("not-offered"));
        try {
            chat.prompt(session, "permit");
            assertEquals(List.of("result:permission=false"), messages);
        } finally {
            chat.close(session);
        }
    }
}
