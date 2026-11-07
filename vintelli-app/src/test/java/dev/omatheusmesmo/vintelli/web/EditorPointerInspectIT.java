package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Replays {@link EditorPointerInspectTest} against the packaged artifact, the Mandrel image under -Pnative. */
@QuarkusIntegrationTest
@WithTestResource(value = EditorPointerInspectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorPointerInspectIT extends EditorPointerInspectTest {
}
