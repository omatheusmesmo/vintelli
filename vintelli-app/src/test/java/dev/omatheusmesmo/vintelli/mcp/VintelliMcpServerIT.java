package dev.omatheusmesmo.vintelli.mcp;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the MCP server assertions of {@link VintelliMcpServerTest} against the
 * packaged artifact built by the native image profile in CI.
 */
@QuarkusIntegrationTest
public class VintelliMcpServerIT extends VintelliMcpServerTest {
}
