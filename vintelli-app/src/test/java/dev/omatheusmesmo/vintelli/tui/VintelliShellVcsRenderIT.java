package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.vcs.TemporaryGitRepositoryResource;
import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link VintelliShellVcsRenderTest} against the packaged artifact, which under {@code -Pnative} is the
 * Mandrel image.
 */
@QuarkusIntegrationTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(value = TemporaryGitRepositoryResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliShellVcsRenderIT extends VintelliShellVcsRenderTest {
}
