package dev.vintelli.spike;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.common.QuarkusTestResource;

@QuarkusIntegrationTest
@QuarkusTestResource(DapTwinResource.class)
class DapProbeIT extends DapProbeTest { }
