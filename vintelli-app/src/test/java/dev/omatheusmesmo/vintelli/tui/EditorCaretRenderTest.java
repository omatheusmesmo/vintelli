package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Opens a project file and reads the editor caret back from the offscreen
 * render: the caret is drawn only while the editor text area is the focused
 * element. {@link EditorCaretRenderIT} replays the same assertions against the
 * packaged native runner.
 */
@QuarkusTest
@WithTestResource(value = EditorTabBarInspectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorCaretRenderTest {

    private static final int EDITOR_FIRST_ROW = 3;

    @TestHTTPResource("/inspect/editor")
    URI editor;

    @Test
    void drawsTheCaretOnTheCursorCellOnlyWhileTheEditorTextAreaHasFocus() {
        given().queryParam("file", "Beta.java").post(URI.create(editor + "/open"))
                .then().statusCode(200);

        given().queryParam("focus", "editor-text-area").when().get("/tui/render")
                .then().statusCode(200)
                .body("editorCarets", hasSize(1),
                        "editorCarets[0].symbol", equalTo("c"),
                        "editorCarets[0].y", equalTo(EDITOR_FIRST_ROW));

        given().when().get("/tui/render")
                .then().statusCode(200)
                .body("editorCarets", empty());
    }
}
