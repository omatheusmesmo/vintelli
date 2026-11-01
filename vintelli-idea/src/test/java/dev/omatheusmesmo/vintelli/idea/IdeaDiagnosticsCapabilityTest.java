package dev.omatheusmesmo.vintelli.idea;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeaDiagnosticsCapabilityTest {

    private static final JsonMapper MCP_JSON = JsonMapper.builder().build();
    private static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(MCP_JSON);
    private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(MCP_JSON);

    @Test
    void reportsIdeaProblems() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "/proj", null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();
            IdeaDiagnosticsCapability capability = new IdeaDiagnosticsCapability(client);
            var result = capability.diagnostics(Paths.get("/proj/src/Foo.java"));
            assertTrue(!result.isFailure(), result.toString());
            var ok = (dev.omatheusmesmo.vintelli.api.capability.CapabilityResult.Ok<java.util.List<dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic>>) result;
            assertEquals(1, ok.value().size());
            assertEquals("cannot find symbol", ok.value().getFirst().message());
        }
    }
}
