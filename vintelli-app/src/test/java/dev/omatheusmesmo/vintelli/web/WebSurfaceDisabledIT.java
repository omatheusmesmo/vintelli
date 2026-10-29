package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link WebSurfaceDisabledTest} against the packaged artifact, which
 * under the {@code native} profile is the Mandrel image: the disabled default
 * and its 404 must hold on the native runner too.
 */
@QuarkusIntegrationTest
class WebSurfaceDisabledIT extends WebSurfaceDisabledTest {
}
