package dev.omatheusmesmo.vintelli.mcp;

import dev.omatheusmesmo.vintelli.web.McpGuardResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the Host and Origin guard assertions of {@link McpEndpointGuardTest}
 * against the packaged artifact built by the native image profile in CI.
 */
@QuarkusIntegrationTest
@WithTestResource(value = McpGuardResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
public class McpEndpointGuardIT extends McpEndpointGuardTest {
}
