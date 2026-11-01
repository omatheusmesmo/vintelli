package dev.omatheusmesmo.vintelli.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * In-process twin of the IntelliJ IDEA MCP server: Streamable HTTP on
 * {@code /stream}, bound to an ephemeral loopback port. It records the request
 * paths and the IDEA headers, and its behavior can be switched while it runs:
 * {@link #rejectionStatus} answers every request with that HTTP status,
 * {@link #toolsCallDelay} delays {@code tools/call}, and {@link #stop()} closes
 * the port so the next request is refused. Tool names select per-call behavior:
 * {@value #ERROR_TOOL} returns a tool-level error and {@value #MALFORMED_TOOL}
 * returns a body that is not JSON.
 *
 * <p>Shared through the test-jar of {@code vintelli-idea} so the integration
 * tests of {@code vintelli-app} can run it as a
 * {@code QuarkusTestResourceLifecycleManager} resource.
 */
public final class FakeIdeaMcpServer implements AutoCloseable {

    public static final String ERROR_TOOL = "get_error_tool";
    public static final String MALFORMED_TOOL = "get_malformed_tool";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private final int port;
    private volatile HttpServer server;
    private volatile int rejectionStatus;
    private volatile Duration toolsCallDelay = Duration.ZERO;

    public FakeIdeaMcpServer() throws IOException {
        server = bind(0);
        port = server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public List<String> paths() {
        return paths;
    }

    public String receivedHeader(String name) {
        return headers.get(name);
    }

    public void rejectWith(int status) {
        rejectionStatus = status;
    }

    public void delayToolCalls(Duration delay) {
        toolsCallDelay = delay;
    }

    public void behaveNormally() {
        rejectionStatus = 0;
        toolsCallDelay = Duration.ZERO;
    }

    public synchronized void stop() {
        HttpServer current = server;
        if (current != null) {
            current.stop(0);
            server = null;
        }
    }

    public synchronized void restart() throws IOException {
        if (server == null) {
            server = bind(port);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private HttpServer bind(int requestedPort) throws IOException {
        HttpServer fresh = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        fresh.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        fresh.createContext("/", this::handle);
        fresh.start();
        return fresh;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            paths.add(exchange.getRequestURI().getPath());
            capture(exchange);
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
                return;
            }
            if ("tools/call".equals(method)) {
                pause(toolsCallDelay);
            }
            respond(exchange, 200, resultFor(method, parsed.path("id"), parsed.path("params").path("name").asText("")));
        }
    }

    private static void pause(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
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

    private static String resultFor(String method, JsonNode id, String toolName) {
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
            case "tools/call" -> toolCallResult(prefix, toolName);
            default -> prefix + "{}}";
        };
    }

    private static String toolCallResult(String prefix, String toolName) {
        return switch (toolName) {
            case ERROR_TOOL -> prefix
                    + "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"IDEA refused the tool\"}]}}";
            case MALFORMED_TOOL -> "this is not a json-rpc payload";
            case "get_run_configurations" -> prefix
                    + "{\"isError\":false,\"content\":[{\"type\":\"text\","
                    + "\"text\":\"{\\\"configurations\\\":[{\\\"name\\\":\\\"Vintelli\\\","
                    + "\\\"description\\\":\\\"Quarkus application\\\","
                    + "\\\"supportsDynamicLaunchOverrides\\\":false}]}\"}],"
                    + "\"structuredContent\":{\"configurations\":[{\"name\":\"Vintelli\","
                    + "\"description\":\"Quarkus application\",\"supportsDynamicLaunchOverrides\":false}]}}}";
            case "execute_run_configuration" -> prefix
                    + "{\"isError\":false,\"content\":[{\"type\":\"text\","
                    + "\"text\":\"{\\\"output\\\":\\\"started\\nfinished\\\","
                    + "\\\"exitCode\\\":0,\\\"sessionId\\\":\\\"s1\\\"}\"}],"
                    + "\"structuredContent\":{\"output\":\"started\\nfinished\",\"exitCode\":0,\"sessionId\":\"s1\"}}}";
            case "get_file_problems" -> prefix
                    + "{\"isError\":false,\"content\":[{\"type\":\"text\","
                    + "\"text\":\"{\\\"filePath\\\":\\\"src/Foo.java\\\","
                    + "\\\"errors\\\":[{\\\"severity\\\":\\\"ERROR\\\","
                    + "\\\"description\\\":\\\"cannot find symbol\\\","
                    + "\\\"line\\\":2,\\\"column\\\":5}]}\"}]}}";
            case "execute_terminal_command" -> prefix
                    + "{\"isError\":false,\"content\":[{\"type\":\"text\","
                    + "\"text\":\"{\\\"command_output\\\":\\\"hello\\\","
                    + "\\\"command_exit_code\\\":0}\"}]}}";
            default -> prefix
                    + "{\"isError\":false,\"content\":[{\"type\":\"text\","
                    + "\"text\":\"Run configuration 'Vintelli' started\"}]}}";
        };
    }
}
