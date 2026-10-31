package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Re-runs {@link ConfiguredTruecolorPaletteTest} against the packaged
 * artifact, which under {@code -Pnative} is the Mandrel image: the authored
 * hex values of the shipped stylesheet have to reach the render payload
 * unchanged off the JVM, and the configured depth has to hold on the native
 * runner, which {@link ConfiguredShippedThemeIT} cannot show because it runs
 * without a forced level and therefore in whatever depth the runner detects.
 */
@QuarkusIntegrationTest
@TestProfile(ConfiguredTruecolorPaletteTest.TruecolorProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredTruecolorPaletteIT extends ConfiguredTruecolorPaletteTest {
}
