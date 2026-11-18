package dev.omatheusmesmo.vintelli.acp;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link VintelliAcpVersionMismatchTest} against the packaged artifact,
 * which under {@code -Pnative} is the Mandrel image.
 */
@QuarkusIntegrationTest
@WithTestResource(EchoAcpAgentVersionMismatchResource.class)
class VintelliAcpVersionMismatchIT extends VintelliAcpVersionMismatchTest {
}
