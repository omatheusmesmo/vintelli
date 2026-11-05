package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Re-runs {@link EditorDocCommentStyleTruecolorTest} against the packaged
 * artifact: the forced truecolor depth and the authored hex values of both
 * shipped stylesheets have to hold off the JVM as well. The profile carries the
 * same configuration the JVM test uses, and the launcher hands it to the
 * packaged application as system properties, exactly as it does for
 * {@link dev.omatheusmesmo.vintelli.tui.ConfiguredTruecolorPaletteIT}.
 */
@QuarkusIntegrationTest
@TestProfile(EditorDocCommentStyleTruecolorTest.TruecolorProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(value = EditorInspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorDocCommentStyleTruecolorIT extends EditorDocCommentStyleTruecolorTest {
}
