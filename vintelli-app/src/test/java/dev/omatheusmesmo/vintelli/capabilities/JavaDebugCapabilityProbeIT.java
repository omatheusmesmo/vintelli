package dev.omatheusmesmo.vintelli.capabilities;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the headless debug capability proof against the packaged application, including its native executable. */
@QuarkusIntegrationTest
@WithTestResource(value = JavaDebugCapabilityProbeResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class JavaDebugCapabilityProbeIT extends JavaDebugCapabilityProbeTest {
}
