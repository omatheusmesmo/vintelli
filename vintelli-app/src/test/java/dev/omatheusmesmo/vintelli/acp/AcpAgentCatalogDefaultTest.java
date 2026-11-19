package dev.omatheusmesmo.vintelli.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AcpAgentCatalogDefaultTest {

    @Inject
    AcpAgentsConfig config;

    @Inject
    AcpAgentCatalog catalog;

    @Test
    void theCatalogIsOnWithoutAnyAcpPropertyAndListsEveryPresetWithItsAvailability() {
        assertTrue(config.enabled());
        assertEquals(Set.of("opencode", "claude", "pi"),
                catalog.agents().stream().map(AcpChatPort.Agent::id).collect(Collectors.toSet()));
        for (AcpChatPort.Agent agent : catalog.agents()) {
            String command = config.agents().get(agent.id()).command();
            assertEquals(agent.available(), agent.reason().isEmpty(), agent.reason());
            if (!agent.available()) {
                assertEquals("Executable not found: " + command, agent.reason());
            }
        }
    }
}
