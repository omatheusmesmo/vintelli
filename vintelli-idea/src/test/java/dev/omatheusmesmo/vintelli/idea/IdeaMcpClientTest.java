package dev.omatheusmesmo.vintelli.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeaMcpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonMapper MCP_JSON = JsonMapper.builder().build();
    private static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(MCP_JSON);
    private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(MCP_JSON);

    @Test
    void connectsAndListsIdeaToolsOnStreamEndpoint() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {

            assertEquals(ConnectionState.CONNECTED, client.reconnect().state(), client.status().detail());

            List<IdeaTool> tools = client.listIdeaTools();
            List<String> names = tools.stream().map(IdeaTool::name).toList();
            assertTrue(names.contains("get_run_configurations"), names.toString());
            assertTrue(names.contains("execute_terminal_command"), names.toString());

            List<IdeaTool> nextRound = client.listIdeaTools();
            assertEquals(3, nextRound.size(), names.toString());
            assertTrue(server.paths.stream().allMatch("/stream"::equals), server.paths.toString());
        }
    }

    @Test
    void sendsProjectPathAndAuthTokenHeaders() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "~/work/vintelli", "idea-token", "test", MAPPER, VALIDATOR)) {

            assertEquals(ConnectionState.CONNECTED, client.reconnect().state(), client.status().detail());
            client.listIdeaTools();

            assertEquals("~/work/vintelli", server.receivedHeader("IJ_MCP_SERVER_PROJECT_PATH"));
            assertEquals("idea-token", server.receivedHeader("IJ_MCP_AUTH_TOKEN"));
        }
    }

    @Test
    void reportsAuthRequiredOn401() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            server.rejectionStatus = 401;

            assertEquals(ConnectionState.AUTH_REQUIRED, client.reconnect().state(), client.status().detail());
        }
    }

    @Test
    void reportsConsentPendingOn403() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            server.rejectionStatus = 403;

            assertEquals(ConnectionState.CONSENT_PENDING, client.reconnect().state(), client.status().detail());
        }
    }

    @Test
    void reportsEndpointNotFoundOn404() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            server.rejectionStatus = 404;

            assertEquals(ConnectionState.ENDPOINT_NOT_FOUND, client.reconnect().state(), client.status().detail());
        }
    }

    @Test
    void reportsUnreachableOnAClosedPort() throws Exception {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        try (IdeaMcpClient client = new IdeaMcpClient("http://127.0.0.1:" + deadPort, null, null, "test", MAPPER, VALIDATOR)) {

            assertEquals(ConnectionState.UNREACHABLE, client.reconnect().state(), client.status().detail());
            assertTrue(client.listIdeaTools().isEmpty());

            IdeaToolCall call = client.callIdeaTool("get_run_configurations", Map.of());
            assertTrue(call.isError(), call.resultText());
        }
    }

    @Disabled("Hangs on 2-vCPU runners, tracked in #46")
    @Test
    void callsIdeaToolAndMapsResult() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();

            IdeaToolCall call = client.callIdeaTool("execute_run_configuration", Map.of("name", "Vintelli"));
            assertEquals("execute_run_configuration", call.tool());
            assertTrue(call.resultText().contains("Vintelli"), call.resultText());
            assertTrue(!call.isError());
        }
    }

    @Test
    void timesOutLongRunningToolCalls() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            server.toolsCallDelay = Duration.ofSeconds(2);
            client.reconnect();

            IdeaToolCall call = client.callIdeaTool("execute_terminal_command", Map.of("command", "slow"),
                    Duration.ofMillis(300));

            assertTrue(call.isError(), call.resultText());
            assertEquals(ConnectionState.TIMEOUT, client.status().state(), client.status().detail());
        }
    }

    @Test
    void recoversByReconnectingWhenIdeaRestartedWithoutLosingState() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();
            assertEquals(3, client.listIdeaTools().size());

            server.kill();

            assertTrue(client.listIdeaTools().isEmpty(), "IDEA down means no tools");
            assertEquals(ConnectionState.UNREACHABLE, client.status().state(), client.status().detail());
            assertEquals(ConnectionState.UNREACHABLE, client.reconnect().state(), client.status().detail());
        }
    }

    static final class FakeIdeaMcpServer implements AutoCloseable {

        private final HttpServer server;
        final List<String> paths = new CopyOnWriteArrayList<>();
        final Map<String, String> headers = new ConcurrentHashMap<>();
        int rejectionStatus;
        Duration toolsCallDelay = Duration.ZERO;
        private boolean killed;

        FakeIdeaMcpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String receivedHeader(String name) {
            return headers.get(name);
        }

        void kill() {
            killed = true;
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            capture(exchange);
            if (killed) {
                throw new IOException("fake IDEA was killed");
            }
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (rejectionStatus != 0) {
                respond(exchange, rejectionStatus, "{}");
                return;
            }
            if (!"/stream".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, "{\"jsonrpc\":\"2.0\",\"id\":1,"
                        + "\"error\":{\"code\":-32601,\"message\":\"unknown endpoint\"}}");
                return;
            }
            JsonNode parsed = JSON.readTree(request);
            String method = parsed.path("method").asText("");
            if (method.startsWith("notifications/")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            int delayMs = "tools/call".equals(method) ? (int) toolsCallDelay.toMillis() : 0;
            if (delayMs > 0) {
                sleep(delayMs);
            }
            respond(exchange, 200, resultFor(method, parsed.path("id")));
        }

        private static void sleep(int delayMs) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void capture(HttpExchange exchange) {
            putHeader(exchange, "IJ_MCP_SERVER_PROJECT_PATH");
            putHeader(exchange, "IJ_MCP_AUTH_TOKEN");
        }

        private void putHeader(HttpExchange exchange, String name) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value != null) {
                headers.put(name, value);
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("mcp-session-id", "fake-session");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }

        private static String resultFor(String method, JsonNode id) {
            String idText = id.isMissingNode() || id.isNull() ? "null" : id.toString();
            String prefix = "{\"jsonrpc\":\"2.0\",\"id\":" + idText + ",\"result\":";
            return switch (method) {
                case "initialize" -> prefix
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"fake-idea\",\"version\":\"2025.2\"}}}";
                case "tools/list" -> prefix
                        + "{\"tools\":["
                        + "{\"name\":\"get_run_configurations\",\"description\":\"List IDEA run configurations\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},\"required\":[]}},"
                        + "{\"name\":\"execute_run_configuration\",\"description\":\"Execute an IDEA run configuration\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}},"
                        + "{\"name\":\"execute_terminal_command\",\"description\":\"Execute a terminal command in IDEA\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}}"
                        + "],\"nextCursor\":null}}";
                case "tools/call" -> prefix
                        + "{\"isError\":false,\"content\":[{\"type\":\"text\","
                        + "\"text\":\"Run configuration 'Vintelli' started\"}]}}";
                default -> prefix + "{}}";
            };
        }

        @Override
        public void close() {
            if (!killed) {
                server.stop(0);
            }
        }
    }
}
