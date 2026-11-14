package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Re-runs {@link WebTerminalIdleTimeoutTest} against the packaged artifact. */
@QuarkusIntegrationTest
@WithTestResource(value = WebTerminalSessionLimitResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalIdleTimeoutIT extends WebTerminalIdleTimeoutTest {
}
