package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;

/**
 * Re-runs {@link VintelliShellRenderTest} against the packaged artifact, which under
 * {@code -Pnative} is the Mandrel image: any render pipeline or JSON serialization
 * failure surfaces here instead of at runtime.
 */
@QuarkusIntegrationTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliShellRenderIT extends VintelliShellRenderTest {
}
