package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;

/**
 * Re-runs {@link VintelliFileIconsTest} against the packaged artifact, which
 * under {@code -Pnative} is the Mandrel image: the built-in
 * {@code file-icons.properties} mapping must be inside the image, so a dropped
 * {@code quarkus.native.resources.includes} line fails the icon inspection
 * load here instead of at the first rendered file tree.
 */
@QuarkusIntegrationTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliFileIconsIT extends VintelliFileIconsTest {
}
