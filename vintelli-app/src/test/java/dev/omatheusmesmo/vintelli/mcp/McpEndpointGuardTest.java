package dev.omatheusmesmo.vintelli.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.web.McpGuardResource;
import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Host and Origin guard on the local endpoints: a browser origin that the
 * CORS filter would accept must still be refused by the guard with 403
 * before a tool runs, the configured origin gets through, and clients that
 * do not send an origin keep working. {@link McpEndpointGuardIT} replays the
 * same assertions against the native runner.
 */
@QuarkusTest
@WithTestResource(value = McpGuardResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
public class McpEndpointGuardTest {

    private static final String ACCEPT = "application/json, text/event-stream";
    private static final String PROBE_FILE = "guard/probe.java";
    private static final String PROBE_MARKER = "> highlight: guard/probe.java:9";
    private static final String PROBE_CALL = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"vintelli_highlight","arguments":{"file":"guard/probe.java","line":9}}}""";
    private static final String STATE_CALL = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"vintelli_state"}}""";

    @TestHTTPResource
    URI baseUri;

    @TestHTTPResource("/mcp")
    URI mcp;

    @TestHTTPResource("/tui/render")
    URI tuiRender;

    @Test
    public void mcpToolsCallWithForeignHostIsRejectedAndNeverRunsTheTool() {
        given().when()
                .headers("Host", "rebound.example.org", "Accept", ACCEPT)
                .body(PROBE_CALL)
                .post(mcp)
                .then()
                .statusCode(403);

        given().when()
                .get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", not(containsString(PROBE_MARKER)));
    }

    @Test
    public void mcpToolsCallWithForeignOriginIsRejectedAndNeverRunsTheTool() {
        given().when()
                .headers("Origin", McpGuardResource.CORS_ONLY_ORIGIN, "Accept", ACCEPT)
                .body(PROBE_CALL)
                .post(mcp)
                .then()
                .statusCode(403);

        given().when()
                .get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", not(containsString(PROBE_MARKER)));
    }

    @Test
    public void renderEndpointRejectsForeignHostAndForeignOrigin() {
        given().when()
                .headers("Host", "rebound.example.org")
                .get(tuiRender)
                .then()
                .statusCode(403);

        given().when()
                .headers("Origin", McpGuardResource.CORS_ONLY_ORIGIN)
                .get(tuiRender)
                .then()
                .statusCode(403);
    }

    @Test
    public void configuredOriginReachesMcpAndRender() {
        given().when()
                .headers("Origin", McpGuardResource.TRUSTED_ORIGIN, "Accept", ACCEPT)
                .body(STATE_CALL)
                .post(mcp)
                .then()
                .statusCode(200)
                .body(containsString("\"result\""))
                .body(not(containsString("\"error\"")));

        given().when()
                .headers("Origin", McpGuardResource.TRUSTED_ORIGIN)
                .get(tuiRender)
                .then()
                .statusCode(200);
    }

    @Test
    public void clientsWithoutOriginKeepWorking() {
        McpAssured.newStreamableClient()
                .setBaseUri(baseUri)
                .build()
                .connect()
                .when()
                    .toolsCall("vintelli_highlight")
                    .withArguments(Map.of("file", "src/Main.java", "line", 7))
                    .withAssert(McpEndpointGuardTest::assertSuccessfulCall)
                    .send()
                .thenAssertResults();

        given().when()
                .get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", containsString("> highlight: src/Main.java:7"));
    }

    @Test
    public void foreignHostIsRejectedOnEveryPathSpellingTheRouterResolves() {
        List<String> notRejected = new ArrayList<>();
        for (String path : List.of("//tui/render", "/./tui/render", "/tui/render/", "/%74ui/render",
                "/tui//render", "/tui/render;x=1", "/tui/x/../render")) {
            int status = rawStatus("GET", path, "rebound.example.org", null, null);
            if (status != 403) {
                notRejected.add(path + " -> " + status);
            }
        }
        assertTrue(notRejected.isEmpty(), "foreign Host must get 403 on every spelling: " + notRejected);
    }

    @Test
    public void mcpToolsCallOnAlternatePathSpellingsWithForeignHostNeverRunsTheTool() {
        List<String> notRejected = new ArrayList<>();
        for (String path : List.of("//mcp", "/./mcp", "/%6dcp", "/mcp/", "/mcp//")) {
            int status = rawStatus("POST", path, "rebound.example.org", null, PROBE_CALL);
            if (status != 403) {
                notRejected.add(path + " -> " + status);
            }
        }
        given().when()
                .get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", not(containsString(PROBE_MARKER)));
        assertTrue(notRejected.isEmpty(), "foreign Host must get 403 on every spelling: " + notRejected);
    }

    @Test
    public void foreignOriginIsRejectedOnAlternatePathSpellings() {
        List<String> notRejected = new ArrayList<>();
        for (String path : List.of("//mcp", "/%6dcp", "/mcp/", "//tui/render", "/tui/render/")) {
            boolean mcpPath = path.contains("mcp");
            int status = rawStatus(mcpPath ? "POST" : "GET", path, null, McpGuardResource.CORS_ONLY_ORIGIN,
                    mcpPath ? PROBE_CALL : null);
            if (status != 403) {
                notRejected.add(path + " -> " + status);
            }
        }
        given().when()
                .get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", not(containsString(PROBE_MARKER)));
        assertTrue(notRejected.isEmpty(), "foreign Origin must get 403 on every spelling: " + notRejected);
    }

    @Test
    public void loopbackSpellingsAreAcceptedAndLookalikesAreRejected() {
        for (String host : List.of("localhost", "LOCALHOST", "127.0.0.1", "[::1]", "[::1]:8080", "localhost:8080")) {
            assertEquals(200, rawStatus("GET", "/tui/render", host, null, null), "accepted host " + host);
        }
        for (String host : List.of("localhost.", "127.0.0.1.", "localhost.evil.example", "127.1", "0x7f.1",
                "[::ffff:127.0.0.1]", "0.0.0.0", "localhost,evil.example")) {
            assertEquals(403, rawStatus("GET", "/tui/render", host, null, null), "rejected host " + host);
        }
        for (String host : List.of("localhost@evil.example", "evil.example@localhost", "evil.example#@localhost")) {
            int status = rawStatus("GET", "/tui/render", host, null, null);
            assertTrue(status == 400 || status == 403, "userinfo host " + host + " must not be served: " + status);
        }
    }

    @Test
    public void originSpellingsThatAreNotTheConfiguredOneAreRejected() {
        for (String origin : List.of("null", "", "http://trusted.example:6274.evil.example",
                "http://trusted.example:9999", "https://trusted.example:6274")) {
            assertEquals(403, rawStatus("GET", "/tui/render", "localhost", origin, null),
                    "rejected origin [" + origin + "]");
        }
    }

    private int rawStatus(String method, String path, String host, String origin, String body) {
        String effectiveHost = host == null ? baseUri.getHost() + ":" + baseUri.getPort() : host;
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(effectiveHost).append("\r\n");
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        if (body != null) {
            request.append("Accept: ").append(ACCEPT).append("\r\n")
                    .append("Content-Type: application/json\r\n");
        }
        request.append("Content-Length: ").append(payload.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket(baseUri.getHost(), baseUri.getPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return Integer.parseInt(reader.readLine().split(" ")[1]);
        } catch (IOException e) {
            throw new IllegalStateException("raw request failed: " + path, e);
        }
    }

    private static void assertSuccessfulCall(ToolResponse response) {
        assertFalse(response.isError(), "tools/call must succeed: " + response);
        List<TextContent> texts = response.content().stream()
                .filter(c -> c.type() == Content.Type.TEXT)
                .map(Content::asText)
                .toList();
        assertFalse(texts.isEmpty(), "tools/call must return text content");
    }
}
