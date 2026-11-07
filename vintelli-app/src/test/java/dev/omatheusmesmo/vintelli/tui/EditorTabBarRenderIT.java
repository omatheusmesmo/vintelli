package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link EditorTabBarRenderTest} against the packaged artifact, which
 * under {@code -Pnative} is the Mandrel image.
 */
@QuarkusIntegrationTest
@WithTestResource(value = EditorTabBarInspectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorTabBarRenderIT extends EditorTabBarRenderTest {
}
