package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Re-runs {@link ConfiguredUserThemeTest} against the packaged artifact,
 * which under {@code -Pnative} is the Mandrel image: the shipped stylesheets,
 * the user theme file, the {@code extends} inheritance and the active theme
 * all go through the CSS engine inside the native binary.
 */
@QuarkusIntegrationTest
@TestProfile(ConfiguredUserThemeTest.MidnightProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredUserThemeIT extends ConfiguredUserThemeTest {
}
