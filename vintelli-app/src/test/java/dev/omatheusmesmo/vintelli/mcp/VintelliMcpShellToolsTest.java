package dev.omatheusmesmo.vintelli.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Round trip of the shell tools: tools/call over streamable HTTP must push
 * state through the {@code vintelli-api} ports and the pushed state must
 * show up on the offscreen render endpoint. {@link VintelliMcpShellToolsIT}
 * replays the very same assertions against the native runner. Both enable
 * {@code vintelli.inspect.enabled} through the test resource.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
public class VintelliMcpShellToolsTest {

    @TestHTTPResource
    URI baseUri;

    @TestHTTPResource("/tui/render")
    URI tuiRender;

    @Test
    public void vintelliStateReturnsTheSelectionAndTheAgentSessions() {
        mcpClient()
                    .toolsCall("vintelli_state")
                    .withAssert(this::assertStatePayload)
                    .send()
                .thenAssertResults();
    }

    @Test
    public void vintelliHighlightPushesFileAndLineToTheShell() {
        mcpClient()
                    .toolsCall("vintelli_highlight")
                    .withArguments(Map.of("file", "src/Main.java", "line", 7))
                    .withAssert(VintelliMcpShellToolsTest::assertSuccessfulCall)
                    .send()
                .thenAssertResults();

        given()
                .when().get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", containsString("> highlight: src/Main.java:7"));
    }

    @Test
    public void vintelliDiffViewRendersTheUnifiedDiffInTheShell() {
        mcpClient()
                    .toolsCall("vintelli_diff_view")
                    .withArguments(Map.of(
                            "title", "example.patch",
                            "diff", "@@ -1,3 +1,4 @@\n vintelli state\n+state view"))
                    .withAssert(VintelliMcpShellToolsTest::assertSuccessfulCall)
                    .send()
                .thenAssertResults();

        given()
                .when().get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", containsString("diff: example.patch"),
                        "editorArea", containsString("@@ -1,3 +1,4 @@"));
    }

    @Test
    public void vintelliHighlightReturnsValidJsonForControlCharactersInTheFileName() {
        mcpClient()
                    .toolsCall("vintelli_highlight")
                    .withArguments(Map.of("file", "src/Ma\u0007in\u000c.java", "line", 3))
                    .withAssert(response -> {
                        assertSuccessfulCall(response);
                        String text = textResult(response);
                        assertDoesNotThrow(() -> new ObjectMapper().readTree(text), text);
                    })
                    .send()
                .thenAssertResults();
    }

    @Test
    public void vintelliHighlightRejectsANegativeLine() {
        mcpClient()
                    .toolsCall("vintelli_highlight")
                    .withArguments(Map.of("file", "src/Main.java", "line", -4))
                    .withAssert(VintelliMcpShellToolsTest::assertRejectedCall)
                    .send()
                .thenAssertResults();
    }

    @Test
    public void vintelliHighlightRejectsAAbsolutePathAndLeavesTheShellUnchanged() {
        mcpClient()
                    .toolsCall("vintelli_highlight")
                    .withArguments(Map.of("file", "/etc/passwd", "line", 1))
                    .withAssert(VintelliMcpShellToolsTest::assertRejectedCall)
                    .send()
                .thenAssertResults();

        given()
                .when().get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", not(containsString("/etc/passwd")));
    }

    @Test
    public void vintelliHighlightRejectsDotDotSegmentsAndLeavesTheShellUnchanged() {
        mcpClient()
                    .toolsCall("vintelli_highlight")
                    .withArguments(Map.of("file", "../pom.xml", "line", 1))
                    .withAssert(VintelliMcpShellToolsTest::assertRejectedCall)
                    .send()
                .thenAssertResults();

        given()
                .when().get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", not(containsString("../pom.xml")));
    }

    @Test
    public void vintelliDiffViewRejectsABlankTitleAndKeepsThePreviousDiff() {
        mcpClient()
                    .toolsCall("vintelli_diff_view")
                    .withArguments(Map.of("title", "kept.patch", "diff", "@@ -1 +1 @@\n-a\n+b"))
                    .withAssert(VintelliMcpShellToolsTest::assertSuccessfulCall)
                    .send()
                    .toolsCall("vintelli_diff_view")
                    .withArguments(Map.of("title", " ", "diff", "@@ -9 +9 @@\n-x\n+y"))
                    .withAssert(VintelliMcpShellToolsTest::assertRejectedCall)
                    .send()
                .thenAssertResults();

        given()
                .when().get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", containsString("kept.patch"),
                        "editorArea", not(containsString("@@ -9 +9 @@")));
    }

    @Test
    public void vintelliDiffViewRejectsAnOversizedDiff() {
        mcpClient()
                    .toolsCall("vintelli_diff_view")
                    .withArguments(Map.of("title", "big.patch", "diff", "x".repeat(256 * 1024 + 1)))
                    .withAssert(VintelliMcpShellToolsTest::assertRejectedCall)
                    .send()
                .thenAssertResults();
    }

    @Test
    public void vintelliDiffViewStripsTerminalControlCharacters() {
        mcpClient()
                    .toolsCall("vintelli_diff_view")
                    .withArguments(Map.of(
                            "title", "esc\u001Bpatch",
                            "diff", "@@ -1 +1 @@\n a\n\u001B[31m+injected"))
                    .withAssert(VintelliMcpShellToolsTest::assertSuccessfulCall)
                    .send()
                .thenAssertResults();

        given()
                .when().get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", containsString("diff: esc patch"),
                        "editorArea", containsString("+injected"),
                        "editorArea", not(containsString("\u001B")));
    }

    @Test
    public void vintelliDiffViewAppendsAMarkerForTruncatedDiffs() {
        mcpClient()
                    .toolsCall("vintelli_diff_view")
                    .withArguments(Map.of("title", "long.patch", "diff", twelveLineDiff()))
                    .withAssert(VintelliMcpShellToolsTest::assertSuccessfulCall)
                    .send()
                .thenAssertResults();

        given()
                .when().get(tuiRender)
                .then()
                .statusCode(200)
                .body("editorArea", containsString("... (6 more lines)"),
                        "editorArea", not(containsString("ctx 10")));
    }

    @Test
    public void vintelliHighlightRejectsABlankFile() {
        mcpClient()
                    .toolsCall("vintelli_highlight")
                    .withArguments(Map.of("file", " ", "line", 1))
                    .withAssert(VintelliMcpShellToolsTest::assertRejectedCall)
                    .send()
                .thenAssertResults();
    }

    @Test
    public void vintelliHighlightRejectsALineOutsideTheFile() {
        mcpClient()
                    .toolsCall("vintelli_highlight")
                    .withArguments(Map.of("file", "src/Main.java", "line", 0))
                    .withAssert(VintelliMcpShellToolsTest::assertRejectedCall)
                    .send()
                .thenAssertResults();
    }

    @Test
    public void vintelliDiffViewRejectsABlankDiff() {
        mcpClient()
                    .toolsCall("vintelli_diff_view")
                    .withArguments(Map.of("title", "example.patch", "diff", " "))
                    .withAssert(VintelliMcpShellToolsTest::assertRejectedCall)
                    .send()
                .thenAssertResults();
    }

    private static String twelveLineDiff() {
        StringBuilder diff = new StringBuilder("@@ -1 +1 @@\n");
        for (int line = 0; line < 11; line++) {
            diff.append(" ctx ").append(line).append('\n');
        }
        return diff.toString();
    }

    private McpAssured.McpStreamableAssert mcpClient() {
        return McpAssured.newStreamableClient()
                .setBaseUri(baseUri)
                .build()
                .connect()
                .when();
    }

    private void assertStatePayload(ToolResponse response) {
        assertSuccessfulCall(response);
        String text = textResult(response);
        var payload = assertDoesNotThrow(() -> new ObjectMapper().readTree(text), text);
        assertEquals("run", payload.path("selectedToolWindowId").asText(), text);
        assertTrue(payload.hasNonNull("selectedFile"), text);
        assertTrue(payload.path("selectedFile").isTextual(), text);
        assertTrue(payload.path("agentSessionIds").isArray(), text);
        assertEquals(0, payload.path("agentSessionIds").size(), text);
    }

    private static void assertSuccessfulCall(ToolResponse response) {
        assertFalse(response.isError(), "tools/call must succeed: " + response);
    }

    private static void assertRejectedCall(ToolResponse response) {
        assertTrue(response.isError(), "invalid arguments must fail the tool call: " + response);
    }

    private static String textResult(ToolResponse response) {
        List<TextContent> texts = response.content().stream()
                .filter(c -> c.type() == Content.Type.TEXT)
                .map(Content::asText)
                .toList();
        assertFalse(texts.isEmpty(), "tools/call must return text content");
        return texts.getFirst().text();
    }
}
