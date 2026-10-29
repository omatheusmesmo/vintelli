package dev.omatheusmesmo.vintelli.mcp;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the shell tools round trip of {@link VintelliMcpShellToolsTest}
 * against the packaged artifact built by the native image profile in CI.
 */
@QuarkusIntegrationTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
public class VintelliMcpShellToolsIT extends VintelliMcpShellToolsTest {
}
