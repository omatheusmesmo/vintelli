package dev.omatheusmesmo.vintelli.acp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AcpAgentCatalogDisabledTest.DisabledProfile.class)
class AcpAgentCatalogDisabledTest {

    @Inject
    AcpAgentCatalog catalog;

    @Test
    void disablingTheCatalogLeavesNoAgentAvailable() {
        assertFalse(catalog.agents().isEmpty());
        assertTrue(catalog.agents().stream().noneMatch(AcpChatPort.Agent::available));
        assertTrue(catalog.agents().stream().allMatch(agent -> agent.reason().contains("vintelli.acp.enabled")));
    }

    public static class DisabledProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("vintelli.acp.enabled", "false");
        }
    }
}
