package dev.omatheusmesmo.vintelli.acp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EchoAcpAgentResourceTest {

    @Test
    void agentArgsPointAtAnArgumentFileInsteadOfInliningTheClasspath() throws IOException {
        EchoAcpAgentResource resource = new EchoAcpAgentResource();
        Map<String, String> properties = resource.start();
        try {
            String agentArgs = properties.get("vintelli.acp.probe.agent-args");
            assertTrue(agentArgs.startsWith("@"), () -> "agent-args must be a JDK argument file: " + agentArgs);
            assertFalse(agentArgs.contains("-cp"), () -> "agent-args must not inline the classpath: " + agentArgs);

            Path file = Path.of(agentArgs.substring(1));
            assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains(EchoAcpAgent.class.getName()));
        } finally {
            resource.stop();
        }
    }

    @Test
    void stopRemovesTheArgumentFile() {
        EchoAcpAgentResource resource = new EchoAcpAgentResource();
        String agentArgs = resource.start().get("vintelli.acp.probe.agent-args");

        resource.stop();

        assertFalse(Files.exists(Path.of(agentArgs.substring(1))));
    }
}
