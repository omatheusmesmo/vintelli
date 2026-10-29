// Fast standalone fake of the IntelliJ IDEA MCP server, launched as an external
// process by the IDEA client integration tests (plain single-file source
// launcher, no dependencies beyond the JDK).
//
// Matches the IDEA semantics the Vintelli client relies on: Streamable HTTP at
// /stream, mcp-session-id on the initialize response, the
// IJ_MCP_SERVER_PROJECT_PATH and IJ_MCP_AUTH_TOKEN request headers, 401 when
// the token is missing or wrong in restricted mode, 403 while the consent gate
// is closed, 404 for every other path, and a tools/list fixture shaped like the
// run, terminal and diagnostics tool categories IDEA exposes.
//
// Usage: java idea_mcp_server.java [port] [expected-token] [consent-granted]
// Defaults: port 8080, token empty (open mode, header not required), consent granted.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class idea_mcp_server {

    private static final Pattern METHOD_PATTERN = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOOL_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|-?\\d+|null|true|false)");
    private static final Pattern PROTOCOL_PATTERN = Pattern.compile("\"protocolVersion\"\\s*:\\s*\"([^\"]+)\"");
    private static final String SESSION_ID = "idea-fake-session";

    private boolean consentGranted;
    private String expectedToken;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String token = args.length > 1 ? args[1] : "";
        boolean consent = args.length < 3 || Boolean.parseBoolean(args[2]);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> new idea_mcp_server(consent, token).handle(exchange));
        server.start();
        System.out.println("fake IDEA MCP server listening on http://127.0.0.1:" + port + "/stream");
    }

    private idea_mcp_server(boolean consentGranted, String expectedToken) {
        this.consentGranted = consentGranted;
        this.expectedToken = expectedToken;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!"/stream".equals(path)) {
                respond(exchange, 404, errorBody("null", -32601, "unknown endpoint " + path));
                return;
            }
            if (!consentGranted) {
                respond(exchange, 403, errorBody("null", -32000, "consent dialog not approved yet"));
                return;
            }
            if (!expectedToken.isEmpty() && !expectedToken.equals(exchange.getRequestHeaders().getFirst("IJ_MCP_AUTH_TOKEN"))) {
                respond(exchange, 401, errorBody("null", -32000, "IJ_MCP_AUTH_TOKEN missing or wrong"));
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "DELETE" -> respond(exchange, 200, "");
                case "GET" -> respond(exchange, 405, errorBody("null", -32601, "SSE streams not supported"));
                case "POST" -> handlePost(exchange);
                default -> respond(exchange, 405, "{}");
            }
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher method = METHOD_PATTERN.matcher(request);
        Matcher id = ID_PATTERN.matcher(request);
        String requestId = id.find() ? id.group(1) : "null";
        if (!method.find()) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        if (method.group(1).startsWith("notifications/")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        Matcher version = PROTOCOL_PATTERN.matcher(request);
        String protocolVersion = version.find() ? version.group(1) : "2025-06-18";
        switch (method.group(1)) {
            case "initialize" -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + requestId
                    + ",\"result\":{\"protocolVersion\":\"" + protocolVersion + "\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"intellij-idea\",\"version\":\"2025.2\"}}}", true);
            case "tools/list" -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + requestId
                    + ",\"result\":{\"tools\":[" + toolCatalog() + "],\"nextCursor\":null}}");
            case "tools/call" -> {
                Matcher tool = TOOL_PATTERN.matcher(request);
                tool.find();
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + requestId
                        + ",\"result\":{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"handled tool " + tool.group(1) + "\"}]}}");
            }
            default -> respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + requestId + ",\"result\":{}}");
        }
    }

    private String toolCatalog() {
        return String.join(",",
                tool("get_run_configurations", "Get the run configurations currently visible in the IntelliJ IDEA Run panel"),
                tool("execute_run_configuration", "Execute a run configuration in IntelliJ IDEA by name; long running"),
                tool("execute_terminal_command", "Execute a command in the IntelliJ IDEA integrated terminal"),
                tool("get_diagnostics", "Get code diagnostics for the current project in IntelliJ IDEA"),
                tool("execute_action", "Execute an IntelliJ IDEA action by id"));
    }

    private static String tool(String name, String description) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description
                + "\",\"inputSchema\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}";
    }

    private String errorBody(String id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body, false);
    }

    private static void respond(HttpExchange exchange, int status, String body, boolean session) throws IOException {
        if (session) {
            exchange.getResponseHeaders().add("mcp-session-id", SESSION_ID);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
