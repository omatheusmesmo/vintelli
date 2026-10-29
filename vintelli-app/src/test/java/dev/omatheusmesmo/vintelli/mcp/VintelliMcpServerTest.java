package dev.omatheusmesmo.vintelli.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Exercises the streamable HTTP MCP server endpoint with the Quarkiverse MCP
 * test client. The same assertions run against the native image through
 * {@link VintelliMcpServerIT}.
 */
@QuarkusTest
public class VintelliMcpServerTest {

    private static final List<String> TOOL_NAMES = List.of("vintelli_state", "vintelli_highlight", "vintelli_diff_view");

    @TestHTTPResource
    URI baseUri;

    @Test
    public void toolsListContainsTheShellTools() {
        McpAssured.newStreamableClient()
                .setBaseUri(baseUri)
                .build()
                .connect()
                .when()
                    .toolsList()
                    .withAssert(this::assertShellToolsListed)
                    .send()
                .thenAssertResults();
    }

    private void assertShellToolsListed(McpAssured.ToolsPage toolsPage) {
        for (String name : TOOL_NAMES) {
            McpAssured.ToolInfo tool = toolsPage.findByName(name);
            assertNotNull(tool, "tools/list must expose " + name);
            assertNotNull(tool.description());
            assertFalse(tool.description().isBlank());
        }
    }

    @Test
    public void toolsCallVintelliStateReturnsTextResult() {
        McpAssured.newStreamableClient()
                .setBaseUri(baseUri)
                .build()
                .connect()
                .when()
                    .toolsCall("vintelli_state")
                    .withAssert(VintelliMcpServerTest::assertTextResult)
                    .send()
                .thenAssertResults();
    }

    private static void assertTextResult(ToolResponse response) {
        assertFalse(response.isError(), "tools/call must succeed: " + response);
        List<TextContent> texts = response.content().stream()
                .filter(c -> c.type() == Content.Type.TEXT)
                .map(Content::asText)
                .toList();
        assertFalse(texts.isEmpty(), "tools/call must return text content");
        assertFalse(texts.getFirst().text().isBlank());
    }
}
