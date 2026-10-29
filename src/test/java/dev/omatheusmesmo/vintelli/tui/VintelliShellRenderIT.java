package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link VintelliShellRenderTest} against the packaged artifact, which under
 * {@code -Pnative} is the Mandrel image: any render pipeline or JSON serialization
 * failure surfaces here instead of at runtime.
 */
@QuarkusIntegrationTest
class VintelliShellRenderIT extends VintelliShellRenderTest {
}
