package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Routes pointer gestures through the editor's own mouse path on the gated
 * inspection surface and reads back the caret, selection and viewport. The
 * Pointer.java fixture has 41 document rows, so the line-number gutter is five
 * cells wide and the text starts at column 5 of the 80 by 24 viewport.
 * {@link EditorPointerInspectIT} replays the same assertions on the packaged app.
 */
@QuarkusTest
@WithTestResource(value = EditorPointerInspectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorPointerInspectTest {

    private static final int TEXT_LEFT = 5;

    @TestHTTPResource("/inspect/editor")
    URI editor;

    @Test
    void pointerGesturesMoveTheCaretSelectionAndViewportWithoutChangingTheText() {
        given().queryParam("file", "Pointer.java").post(URI.create(editor + "/open"))
                .then().statusCode(200);

        gesture("PRESS", TEXT_LEFT + 2, 3)
                .body("line", is(3), "column", is(2), "anchorLine", is(3), "anchorColumn", is(2),
                        "selectedText", is(""), "scrollRow", is(0));

        gesture("DRAG", TEXT_LEFT + 4, 5)
                .body("anchorLine", is(3), "line", is(5), "column", is(4),
                        "selectedText", is("ne 4\nline 5\nline"));

        gesture("SCROLL_DOWN", TEXT_LEFT + 4, 5)
                .body("scrollRow", is(3), "line", is(5), "column", is(4));

        gesture("RELEASE", TEXT_LEFT + 4, 5)
                .body("selectedText", is("ne 4\nline 5\nline"));

        gesture("DRAG", TEXT_LEFT + 9, 9)
                .body("line", is(5), "column", is(4), "selectedText", is("ne 4\nline 5\nline"));

        gesture("PRESS", 1, 2)
                .body("line", is(5), "column", is(4), "selectedText", is("ne 4\nline 5\nline"));

        given().when().get(URI.create(editor + "/pointer"))
                .then().statusCode(200).body("line", is(5), "scrollRow", is(3));

        given().when().get(editor)
                .then().statusCode(200).body("dirty", is(false));
    }

    @Test
    void rejectsUnknownGesturesAndPointersOutsideTheViewport() {
        given().queryParam("gesture", "TAP").queryParam("x", 1).queryParam("y", 1)
                .post(URI.create(editor + "/pointer"))
                .then().statusCode(400);
        given().queryParam("gesture", "PRESS").queryParam("x", 80).queryParam("y", 1)
                .post(URI.create(editor + "/pointer"))
                .then().statusCode(400);
        given().queryParam("gesture", "PRESS").queryParam("x", 1).queryParam("y", 1).queryParam("width", 2)
                .post(URI.create(editor + "/pointer"))
                .then().statusCode(400);
    }

    private io.restassured.response.ValidatableResponse gesture(String name, int x, int y) {
        return given().queryParam("gesture", name).queryParam("x", x).queryParam("y", y)
                .post(URI.create(editor + "/pointer"))
                .then().statusCode(200);
    }
}
