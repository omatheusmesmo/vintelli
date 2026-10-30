package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * The shipped {@code darcula} stylesheet as the configured default, with a
 * config directory that holds no user file, so the active sheet is the
 * packaged resource itself. {@link ConfiguredShippedThemeIT} replays it on
 * the native runner, which fails if the stylesheet is not part of the image
 * or the engine cannot style the shell from it.
 */
@QuarkusTest
@TestProfile(ConfiguredShippedThemeTest.DarculaProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredShippedThemeTest {

    public static class DarculaProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "vintelli.tui.theme", "darcula",
                    "vintelli.config.dir", Path.of("target/configured-shipped-theme-test-empty").toAbsolutePath().toString());
        }
    }

    @Test
    void theShippedDarculaStylesheetIsTheConfiguredActiveTheme() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("theme", is("darcula"),
                        "mainAreaTop", containsString("Project"));
    }
}
