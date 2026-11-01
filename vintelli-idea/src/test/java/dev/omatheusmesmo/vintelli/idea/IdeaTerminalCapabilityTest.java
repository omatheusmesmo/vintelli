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
    void rejectsTimedOutTerminalResultsAndPreservesPartialOutput() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "/proj", null, "test", MAPPER, VALIDATOR)) {
            server.terminalResult("{\"command_output\":\"partial\",\"command_exit_code\":124,\"is_timed_out\":true}");
            client.reconnect();

            var result = new IdeaTerminalCapability(client).execute(
                    new TerminalCapability.TerminalRequest("echo hi", Paths.get("/proj"), true));

            assertTrue(result.isFailure(), result.toString());
            assertTrue(result.failureOf().orElseThrow().reason().contains("timed out"));
            assertEquals("partial", result.failureOf().orElseThrow().detail());
        }
    }

    @Test
    void rejectsTerminalResultsWithoutExitCode() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "/proj", null, "test", MAPPER, VALIDATOR)) {
            server.terminalResult("{\"command_output\":\"partial\"}");
            client.reconnect();

            var result = new IdeaTerminalCapability(client).execute(
                    new TerminalCapability.TerminalRequest("echo hi", Paths.get("/proj"), true));

            assertTrue(result.isFailure(), result.toString());
            assertTrue(result.failureOf().orElseThrow().reason().contains("missing an exit code"));
            assertEquals("partial", result.failureOf().orElseThrow().detail());
        }
    }

    @Test
    void rejectsMalformedTerminalJson() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "/proj", null, "test", MAPPER, VALIDATOR)) {
            server.terminalResult("not-json");
            client.reconnect();

            var result = new IdeaTerminalCapability(client).execute(
                    new TerminalCapability.TerminalRequest("echo hi", Paths.get("/proj"), true));

            assertTrue(result.isFailure(), result.toString());
            assertTrue(result.failureOf().orElseThrow().reason().contains("Cannot parse terminal result"));
        }
    }

    @Test
    void rejectsTerminalResultWithANonIntegerExitCode() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "/proj", null, "test", MAPPER, VALIDATOR)) {
            server.terminalResult("{\"command_output\":\"partial\",\"command_exit_code\":\"0\"}");
            client.reconnect();

            var result = new IdeaTerminalCapability(client).execute(
                    new TerminalCapability.TerminalRequest("echo hi", Paths.get("/proj"), true));

            assertTrue(result.isFailure(), result.toString());
            assertTrue(result.failureOf().orElseThrow().reason().contains("Cannot parse terminal result"));
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
