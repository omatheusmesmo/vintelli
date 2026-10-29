package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link VintelliProcessProbeTest} against the packaged artifact, so
 * the real {@link dev.omatheusmesmo.vintelli.local.ProcessRunner} (process
 * launch, timeout via {@code Process.onExit()}, the bounded drain and
 * {@link dev.omatheusmesmo.vintelli.local.LocalProjectRoot} confinement)
 * survives AOT compilation in the native image.
 */
@QuarkusIntegrationTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliProcessProbeIT extends VintelliProcessProbeTest {
}
