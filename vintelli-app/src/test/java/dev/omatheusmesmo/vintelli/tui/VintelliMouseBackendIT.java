package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link VintelliMouseBackendTest} against the packaged artifact (the
 * Mandrel native image under {@code -Dnative}), proving {@code vintelli-jline3}
 * is packaged for {@code ServiceLoader} discovery and drives JLine
 * {@code trackMouse} transitions.
 */
@QuarkusIntegrationTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliMouseBackendIT extends VintelliMouseBackendTest {
}

