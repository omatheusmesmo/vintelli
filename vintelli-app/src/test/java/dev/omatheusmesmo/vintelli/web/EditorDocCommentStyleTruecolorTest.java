package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The truecolor counterpart of {@link EditorDocCommentStyleTest}: with
 * {@code vintelli.tui.color-level=truecolor} forced, the inspection payload
 * reports the authored hex value of the editor runs instead of the rewritten
 * index, so the tag colors of issue #171 are pinned on the depth a truecolor
 * terminal renders. The forced depth differs from the one this environment
 * detects (256 colors), so the same runner cannot make this class and the
 * indexed axis pass without reading the configured depth.
 *
 * <p>The theme is switched inside the test, so both shipped stylesheets are
 * pinned by their own hex value: at 256 colors the two keyword values collapse
 * onto the same index and the switch would not be observable there. The default
 * theme is restored, so no other test inherits the switched one.
 */
@QuarkusTest
@TestProfile(EditorDocCommentStyleTruecolorTest.TruecolorProfile.class)
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(value = EditorInspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorDocCommentStyleTruecolorTest {

    public static class TruecolorProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "vintelli.tui.color-level", "truecolor",
                    "vintelli.config.dir", Path.of("target/editor-doc-comment-style-truecolor-test-empty").toAbsolutePath().toString());
        }
    }

    @Test
    void reportsTheTagInTheAuthoredHexValueOfTheActiveTheme() {
        JsonPath dark = typeIntoAFreshFixture("/**\n * @param x <p>head</p>\n */\n");
        assertEquals("truecolor", dark.getString("colorLevel"));
        assertEquals("#CF8E6D", dark.getString("colors.'editor-keyword'"), "the dark keyword hex");
        assertEquals("#7A7E85", dark.getString("colors.'editor-comment'"), "the dark comment hex");
        List<Map<String, Object>> darkRuns = dark.getList("editorRuns");
        assertEquals("#CF8E6D", colorOfRunContaining(darkRuns, "@param"), "the dark tag run: " + darkRuns);
        assertEquals("#CF8E6D", colorOfRunContaining(darkRuns, "<p>"), "the dark HTML tag run: " + darkRuns);
        assertEquals("#7A7E85", colorOfRunContaining(darkRuns, "head"), "the dark prose run: " + darkRuns);

        try {
            JsonPath darcula = JsonPath.from(given().when().post("/tui/theme?name=darcula").then()
                    .statusCode(200).extract().asString());
            assertEquals("darcula", darcula.getString("theme"));
            assertEquals("#CC7832", darcula.getString("colors.'editor-keyword'"), "the darcula keyword hex");
            List<Map<String, Object>> darculaRuns = darcula.getList("editorRuns");
            assertEquals("#CC7832", colorOfRunContaining(darculaRuns, "@param"), "the darcula tag run: " + darculaRuns);
            assertEquals("#CC7832", colorOfRunContaining(darculaRuns, "<p>"), "the darcula HTML tag run: " + darculaRuns);
            assertEquals("#808080", colorOfRunContaining(darculaRuns, "head"), "the darcula prose run: " + darculaRuns);
            assertNotEquals(colorOfRunContaining(darkRuns, "@param"), colorOfRunContaining(darculaRuns, "@param"),
                    "the two shipped themes must paint the tag in different hex values");
        } finally {
            given().when().post("/tui/theme?name=" + ShellThemes.DEFAULT_THEME).then().statusCode(200);
        }
    }

    /**
     * Restores the fixture file from disk and types the text at the top of the
     * document, then returns the render payload of that edit, like the helper of
     * {@link EditorDocCommentStyleTest}.
     */
    private static JsonPath typeIntoAFreshFixture(String text) {
        given().queryParam("file", "Editor.java").post("/inspect/editor/open").then().statusCode(200);
        for (int undos = 0; undos < 32; undos++) {
            if (given().post("/inspect/editor/undo").then().extract().statusCode() != 200) {
                given().when().get("/inspect/editor").then().statusCode(200)
                        .body("text", is("class Editor {}\n")).body("dirty", is(false));
                given().contentType("application/json").body(Map.of("text", text))
                        .post("/inspect/editor/insert")
                        .then().statusCode(200).body("text", containsString(text.strip()));
                return JsonPath.from(given().when().get("/tui/render").then().statusCode(200).extract().asString());
            }
        }
        throw new AssertionError("the fixture was not restored after 32 undos");
    }

    private static String colorOfRunContaining(List<Map<String, Object>> runs, String text) {
        return runs.stream()
                .filter(run -> String.valueOf(run.get("text")).contains(text))
                .map(run -> String.valueOf(run.get("fg")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no editor run carries '" + text + "': " + runs));
    }
}
