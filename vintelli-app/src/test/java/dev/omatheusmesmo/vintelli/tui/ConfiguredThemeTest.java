package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * The default theme is configuration, not a constant: a profile setting
 * {@code vintelli.tui.theme} must reach the inspect render payload, and the
 * user theme file the same run resolves from
 * {@code vintelli.config.dir} (a temp directory under {@code target}, never
 * the developer's real config home) must define that theme. Renaming the
 * property or breaking the config directory resolution fails this test.
 */
@QuarkusTest
@TestProfile(ConfiguredThemeTest.DarculaProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredThemeTest {

    public static class DarculaProfile implements QuarkusTestProfile {

        static {
            writeUserTheme("target/configured-theme-test/vintelli/themes.tcss",
                    "@theme darcula extends dark\n$panel: #121212;\n");
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "vintelli.tui.theme", "darcula",
                    "vintelli.config.dir", "target/configured-theme-test");
        }
    }

    private static void writeUserTheme(String path, String content) {
        try {
            Files.createDirectories(Path.of(path).getParent());
            Files.writeString(Path.of(path), content);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void theConfiguredDefaultThemeIsTheActiveOne() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("theme", is("darcula"),
                        "status", is("Vintelli ready | IDEA MCP: disconnected | ACP: stopped"));
    }
}
