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
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;

/**
 * The shipped {@code darcula} stylesheet with the 256 color depth forced by
 * {@code vintelli.tui.color-level}: the inspection payload reports the
 * rewritten index of every authored hex, so the assertions do not depend on
 * the terminal environment of the runner. The numbers are the pinned indices
 * of {@link PaletteDowngradeTest}, read from the shipped stylesheet through
 * the inspected theme again. {@link ConfiguredIndexedPaletteIT} replays this
 * on the native runner, where the rewrite of the stylesheet, the index search
 * and the style resolution from an indexed value all have to survive AOT
 * compilation.
 */
@QuarkusTest
@TestProfile(ConfiguredIndexedPaletteTest.Indexed256Profile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ConfiguredIndexedPaletteTest {

    public static class Indexed256Profile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "vintelli.tui.theme", "darcula",
                    "vintelli.tui.color-level", "256",
                    "vintelli.config.dir", Path.of("target/configured-indexed-palette-test-empty").toAbsolutePath().toString());
        }
    }

    /**
     * The active depth is the forced 256 color level, never a detection, so
     * the pinned indices hold whatever the runner advertises.
     */
    @Test
    void theForcedDepthServesThePaletteAsTheRewrittenIndex() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("theme", is("darcula"),
                        "colorLevel", is("indexed_256"),
                        "mainAreaTop", containsString("╭"),
                        "colors.panel", is("indexed(237)"),
                        "colors.chrome-fg", is("indexed(250)"),
                        "colors.tab-selected-bg", is("indexed(60)"),
                        "colors.editor-bg", is("indexed(235)"),
                        "colors.editor-keyword", is("indexed(173)"),
                        "colors.editor-comment", is("indexed(244)"));
    }

    /**
     * The frame of the same payload is still painted: the indexed palette
     * must not cost the shell its render, which is the failure the native
     * runner would report.
     */
    @Test
    void theShellStillPaintsItsFrameInTheIndexedPalette() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("tabRow", containsString("Project"),
                        "mainAreaTop", not(isEmptyOrNullString()),
                        "status", not(isEmptyOrNullString()));
    }
}
