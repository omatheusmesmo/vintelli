package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Re-runs {@link ConfiguredShippedThemeTest} against the packaged artifact,
 * which under {@code -Pnative} is the Mandrel image.
 */
@QuarkusIntegrationTest
@TestProfile(ConfiguredShippedThemeTest.DarculaProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredShippedThemeIT extends ConfiguredShippedThemeTest {
}
