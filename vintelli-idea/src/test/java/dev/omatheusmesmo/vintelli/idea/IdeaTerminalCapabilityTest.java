package dev.omatheusmesmo.vintelli.idea;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeaTerminalCapabilityTest {

    private static final JsonMapper MCP_JSON = JsonMapper.builder().build();
    private static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(MCP_JSON);
    private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(MCP_JSON);

    @Test
    void executesTerminalCommand() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "/proj", null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();
            IdeaTerminalCapability capability = new IdeaTerminalCapability(client);
            var result = capability.execute(new TerminalCapability.TerminalRequest("echo hi", Paths.get("/proj"), false));
            assertTrue(!result.isFailure(), result.toString());
            var ok = (dev.omatheusmesmo.vintelli.api.capability.CapabilityResult.Ok<dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalResult>) result;
            assertEquals(dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalOutcome.EXECUTED, ok.value().outcome());
            assertEquals("hello", ok.value().output());
        }
    }

    @Test
    void doesNotCallIdeaForDangerousCommandWithoutConfirmation() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "/proj", null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();
            IdeaTerminalCapability capability = new IdeaTerminalCapability(client);
            var result = capability.execute(new TerminalCapability.TerminalRequest("rm -rf /", Paths.get("/proj"), false));
            assertTrue(!result.isFailure(), result.toString());
            var ok = (dev.omatheusmesmo.vintelli.api.capability.CapabilityResult.Ok<dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalResult>) result;
            assertEquals(dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalOutcome.REQUIRES_CONFIRMATION, ok.value().outcome());
        }
    }
}
