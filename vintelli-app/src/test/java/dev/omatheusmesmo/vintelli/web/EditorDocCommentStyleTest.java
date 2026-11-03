package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The rendered editor rows expose the foreground color of every run through
 * the gated inspect surface, so the Javadoc tag colors of issue #171 are
 * observable without a terminal: one doc comment is inserted through the
 * editor surface and the frame it paints is read back from the render
 * surface, both on the same private project fixture. The native runner
 * replays the same assertions in {@code EditorDocCommentStyleIT}.
 *
 * <p>The class covers the JVM axes the render payload offers: the doc comment
 * against the plain block and line comment that carry the same tag text, and
 * the theme switch through {@code POST /tui/theme}. The truecolor axis lives in
 * {@link EditorDocCommentStyleTruecolorTest}, which forces the color depth this
 * environment would not detect.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(value = EditorInspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorDocCommentStyleTest {

    @Test
    void paintsJavadocAndHtmlTagsInTheKeywordColorAndTheProseInTheCommentColor() {
        JsonPath payload = typeIntoAFreshFixture("/**\n * @param x <p>text</p>\n */\n");
        String keyword = payload.getString("colors.'editor-keyword'");
        String comment = payload.getString("colors.'editor-comment'");
        assertNotEquals(keyword, comment, "the two palette roles must be distinct to tell the runs apart");

        List<Map<String, Object>> runs = payload.getList("editorRuns");
        assertEquals(keyword, colorOfRunContaining(runs, "@param"), "the Javadoc tag run: " + runs);
        assertEquals(keyword, colorOfRunContaining(runs, "<p>"), "the HTML tag run: " + runs);
        assertEquals(comment, colorOfRunContaining(runs, "text"), "the prose run: " + runs);
        assertNotEquals(colorOfRunContaining(runs, "@param"), colorOfRunContaining(runs, "text"),
                "the tag must not paint in the prose color: " + runs);
    }

    /**
     * The transition the issue asks for, in one payload pair: the very same tag
     * text paints in the keyword role inside a doc comment and stays prose in a
     * plain block or line comment. Both halves run here, so the test fails when
     * the doc detection at the opener or the tag emission regresses, instead of
     * only asserting the absence of a run.
     */
    @Test
    void paintsTagsInsideADocCommentAndLeavesTheSameTextPlainInOtherComments() {
        JsonPath docPayload = typeIntoAFreshFixture("/**\n * @param x <p>head</p>\n */\n");
        String docKeyword = docPayload.getString("colors.'editor-keyword'");
        List<Map<String, Object>> docRuns = docPayload.getList("editorRuns");
        assertEquals(docKeyword, colorOfRunContaining(docRuns, "@param"), "the doc tag run: " + docRuns);
        assertEquals(docKeyword, colorOfRunContaining(docRuns, "<p>"), "the doc HTML tag run: " + docRuns);
        assertEquals(docPayload.getString("colors.'editor-comment'"), colorOfRunContaining(docRuns, "head"),
                "the doc prose run: " + docRuns);

        JsonPath plainPayload = typeIntoAFreshFixture("/* block @param bd <p>bh</p> */\n// line @param ld <p>lh</p>\n");
        String plainComment = plainPayload.getString("colors.'editor-comment'");
        List<Map<String, Object>> plainRuns = plainPayload.getList("editorRuns");
        assertEquals(plainComment, colorOfRunContaining(plainRuns, "/* block"), "the block comment prose: " + plainRuns);
        assertEquals(plainComment, colorOfRunContaining(plainRuns, "// line"), "the line comment prose: " + plainRuns);
        assertEquals(plainComment, colorOfRunContaining(plainRuns, "@param"), "the tag text in a plain comment: " + plainRuns);
    }

    /**
     * The tag keeps the keyword role of whichever theme is active: the frame
     * returned by {@code POST /tui/theme} is read against the palette of that
     * same payload, so the assertion follows the switched theme and not the
     * colors of the test environment. The default theme is restored, so the
     * render endpoint stays independent of the test order.
     */
    @Test
    void paintsTheTagInTheKeywordRoleOfTheSwitchedTheme() {
        try {
            given().when().post("/tui/theme?name=darcula").then().statusCode(200).body("theme", is("darcula"));
            JsonPath payload = typeIntoAFreshFixture("/**\n * @param x <p>head</p>\n */\n");
            assertEquals("darcula", payload.getString("theme"), "the payload must carry the switched theme");
            String keyword = payload.getString("colors.'editor-keyword'");
            String comment = payload.getString("colors.'editor-comment'");
            assertNotEquals(keyword, comment, "the darcula palette must tell its roles apart: " + payload.getString("colors"));
            List<Map<String, Object>> runs = payload.getList("editorRuns");
            assertEquals(keyword, colorOfRunContaining(runs, "@param"), "the tag run in darcula: " + runs);
            assertEquals(keyword, colorOfRunContaining(runs, "<p>"), "the HTML tag run in darcula: " + runs);
            assertEquals(comment, colorOfRunContaining(runs, "head"), "the prose run in darcula: " + runs);
        } finally {
            given().when().post("/tui/theme?name=" + ShellThemes.DEFAULT_THEME).then().statusCode(200);
        }
    }

    /**
     * Restores the fixture file from disk and types the text at the top of the
     * document, then returns the render payload of that edit. The editor keeps
     * an already opened file in memory, so the edit history an earlier test
     * method left behind is drained first: every test then starts from the text
     * on disk and does not depend on the order it runs in.
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
