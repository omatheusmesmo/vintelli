package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link WebTerminalPageDisabledTest} against the packaged artifact.
 */
@QuarkusIntegrationTest
@WithTestResource(value = WebTerminalDisabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalPageDisabledIT extends WebTerminalPageDisabledTest {
}
