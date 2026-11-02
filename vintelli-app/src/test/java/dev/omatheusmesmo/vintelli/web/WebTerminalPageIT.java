package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link WebTerminalPageTest} against the packaged artifact, which
 * under the {@code native} profile is the Mandrel image: the gated page and
 * script, and the xterm.js assets, must all be served by the native runner.
 */
@QuarkusIntegrationTest
@WithTestResource(value = WebTerminalResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalPageIT extends WebTerminalPageTest {
}
