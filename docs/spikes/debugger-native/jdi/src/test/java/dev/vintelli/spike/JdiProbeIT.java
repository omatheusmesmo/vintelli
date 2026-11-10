package dev.vintelli.spike;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.common.QuarkusTestResource;

@QuarkusIntegrationTest
@QuarkusTestResource(DebuggeeResource.class)
class JdiProbeIT extends JdiProbeTest { }
