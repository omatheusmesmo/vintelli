package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Opens two project files through the editor inspection surface and reads the
 * tab bar back from the offscreen render. {@link EditorTabBarRenderIT} replays
 * the same assertions against the packaged native runner.
 */
@QuarkusTest
@WithTestResource(value = EditorTabBarInspectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorTabBarRenderTest {

    @TestHTTPResource("/inspect/editor")
    URI editor;

    @Test
    void rendersEveryOpenFileInTheTabBarWithAnUnderlineUnderTheSelectedOne() {
        given().queryParam("file", "Alpha.java").post(URI.create(editor + "/open"))
                .then().statusCode(200);
        given().queryParam("file", "Beta.java").post(URI.create(editor + "/open"))
                .then().statusCode(200);

        given().when().get("/tui/render")
                .then().statusCode(200)
                .body("tabRow", containsString("Alpha.java"),
                        "tabRow", containsString("Beta.java"),
                        "editorArea", matchesPattern("(?s).*(-{5}|━{5}).*"));
    }
}
