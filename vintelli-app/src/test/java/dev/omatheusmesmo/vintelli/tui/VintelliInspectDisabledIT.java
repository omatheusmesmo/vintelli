package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the disabled-inspect assertions of {@link VintelliInspectDisabledTest}
 * against the packaged artifact built by the native image profile in CI.
 */
@QuarkusIntegrationTest
class VintelliInspectDisabledIT extends VintelliInspectDisabledTest {
}
