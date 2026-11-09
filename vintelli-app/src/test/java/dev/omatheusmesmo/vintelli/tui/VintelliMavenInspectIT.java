package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link VintelliMavenInspectTest} against the packaged artifact, which
 * under {@code -Pnative} is the Mandrel image: the JDK DOM parser behind the
 * Maven tool window must work inside the image, so a parser it cannot start
 * fails the pom read here instead of at the first opening of the window.
 */
@QuarkusIntegrationTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliMavenInspectIT extends VintelliMavenInspectTest {
}
