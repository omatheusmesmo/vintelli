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

    @TestHTTPResource
    URI baseUri;

    @Test
    public void toolsListContainsVintelliState() {
        McpAssured.newStreamableClient()
                .setBaseUri(baseUri)
                .build()
                .connect()
                .when()
                    .toolsList()
                    .withAssert(this::assertVintelliStateListed)
                    .send()
                .thenAssertResults();
    }

    private void assertVintelliStateListed(McpAssured.ToolsPage toolsPage) {
        McpAssured.ToolInfo tool = toolsPage.findByName("vintelli_state");
        assertNotNull(tool, "tools/list must expose vintelli_state");
        assertNotNull(tool.description());
        assertFalse(tool.description().isBlank());
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
