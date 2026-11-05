package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Re-runs {@link EditorDocCommentStyleTest} against the packaged artifact, which
 * under {@code -Pnative} is the Mandrel image: the Javadoc tag colors of issue
 * #171 have to reach the inspect payload off the JVM as well, and the switched
 * theme has to paint the runs in its own keyword role there too.
 */
@QuarkusIntegrationTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(value = EditorInspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorDocCommentStyleIT extends EditorDocCommentStyleTest {
}
