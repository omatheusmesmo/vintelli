package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Re-runs {@link ConfiguredIndexedPaletteTest} against the packaged artifact,
 * which under {@code -Pnative} is the Mandrel image: the stylesheet rewrite,
 * the index search and the style resolution from an indexed value, plus the
 * serialization of the inspected palette, are all exercised off the JVM.
 * Together with {@link ConfiguredTruecolorPaletteTest} the pair shows that
 * the configured depth decides the palette: an environment that would detect
 * the other depth cannot make the forced one pass.
 */
@QuarkusIntegrationTest
@TestProfile(ConfiguredIndexedPaletteTest.Indexed256Profile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredIndexedPaletteIT extends ConfiguredIndexedPaletteTest {
}
