package dev.omatheusmesmo.vintelli.idea;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeaRunCapabilityTest {

    private static final JsonMapper MCP_JSON = JsonMapper.builder().build();
    private static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(MCP_JSON);
    private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(MCP_JSON);

    @Test
    void listsAndRunsIdeaRunConfigurations() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "/proj", null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();
            IdeaRunCapability capability = new IdeaRunCapability(client);

            var listed = capability.listConfigurations();
            assertTrue(!listed.isFailure(), listed.toString());
            CapabilityResult.Ok<List<RunCapability.RunConfiguration>> okList =
                    (CapabilityResult.Ok<List<RunCapability.RunConfiguration>>) listed;
            assertEquals(1, okList.value().size());
            assertEquals("Vintelli", okList.value().getFirst().identifier());

            List<String> lines = new ArrayList<>();
            var started = capability.start(
                    new RunCapability.RunRequest("vintelli-app", java.nio.file.Paths.get("/proj"), Map.of()),
                    lines::add);
            assertTrue(!started.isFailure(), started.toString());
            assertEquals(List.of("started", "finished", "Run finished with exit code 0"), lines);

            server.runOutput("started\r\nfinished");
            lines.clear();
            var crlfStarted = capability.start(
                    new RunCapability.RunRequest("vintelli-app", java.nio.file.Paths.get("/proj"), Map.of()),
                    lines::add);
            assertTrue(!crlfStarted.isFailure(), crlfStarted.toString());
            assertEquals(List.of("started", "finished", "Run finished with exit code 0"), lines);
        }
    }
}
