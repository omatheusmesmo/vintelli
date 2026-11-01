package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Replays project-file actions against the packaged JVM or native application. */
@QuarkusIntegrationTest
@WithTestResource(value = ProjectTreeResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ProjectTreeIT extends ProjectTreeTest {
}
