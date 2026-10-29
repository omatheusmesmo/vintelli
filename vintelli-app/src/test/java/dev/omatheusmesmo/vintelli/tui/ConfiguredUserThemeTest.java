package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * A theme that exists only in the user theme file: the configured default
 * can be {@code midnight} only if the config directory resolves, the file is
 * read and parsed by the stylesheet engine and the {@code extends dark}
 * inheritance completes the palette. {@link ConfiguredUserThemeIT} replays
 * it on the native runner, which proves the stylesheets and the engine work
 * in the image. The directory is absolute under {@code target}, never the
 * developer's real config home.
 */
@QuarkusTest
@TestProfile(ConfiguredUserThemeTest.MidnightProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredUserThemeTest {

    public static class MidnightProfile implements QuarkusTestProfile {

        private static final Path CONFIG_DIR = Path.of("target/configured-user-theme-test").toAbsolutePath();

        static {
            writeUserTheme(CONFIG_DIR.resolve("vintelli").resolve("themes.tcss"),
                    "@theme midnight extends dark\n$panel: #101010;\n");
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "vintelli.tui.theme", "midnight",
                    "vintelli.config.dir", CONFIG_DIR.toString());
        }
    }

    private static void writeUserTheme(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void aThemeDefinedOnlyInTheUserFileIsTheConfiguredActiveTheme() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("theme", is("midnight"),
                        "tabRow", containsString("1. Run"),
                        "status", is("Vintelli ready | IDEA MCP: disconnected | ACP: stopped"));
    }
}
