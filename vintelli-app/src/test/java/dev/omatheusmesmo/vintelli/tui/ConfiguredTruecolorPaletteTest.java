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
import static org.hamcrest.Matchers.is;

/**
 * The counterpart of {@link ConfiguredIndexedPaletteTest}: with the truecolor
 * depth forced by {@code vintelli.tui.color-level} the inspection payload
 * reports the authored hex values of the shipped {@code darcula} stylesheet.
 * {@link ConfiguredTruecolorPaletteIT} replays this on the native runner, so
 * each branch of {@code vintelli.tui.color-level} has its own {@code *IT}. The
 * pair proves the configured depth decides the palette: a runner that would
 * detect the other depth cannot make both classes pass at once.
 */
@QuarkusTest
@TestProfile(ConfiguredTruecolorPaletteTest.TruecolorProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredTruecolorPaletteTest {

    public static class TruecolorProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "vintelli.tui.theme", "darcula",
                    "vintelli.tui.color-level", "truecolor",
                    "vintelli.config.dir", Path.of("target/configured-truecolor-palette-test-empty").toAbsolutePath().toString());
        }
    }

    @Test
    void theForcedDepthServesTheAuthoredHexValues() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("theme", is("darcula"),
                        "colorLevel", is("truecolor"),
                        "colors.panel", is("#3C3F41"),
                        "colors.chrome-fg", is("#BBBBBB"),
                        "colors.tab-selected-bg", is("#365880"),
                        "colors.editor-bg", is("#2B2B2B"),
                        "colors.editor-keyword", is("#CC7832"),
                        "colors.editor-comment", is("#808080"));
    }
}
