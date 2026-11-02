package dev.omatheusmesmo.vintelli.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Enabled browser terminal page: the xterm.js client is served to an
 * authenticated browser, refused without credentials, and its library comes
 * from the managed mvnpm artifact. {@link WebTerminalPageIT} replays the same
 * assertions on the native runner.
 */
@QuarkusTest
@WithTestResource(value = WebTerminalResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalPageTest {

    static final String PAGE = "/tui/terminal";
    static final String SCRIPT = "/tui/terminal/terminal.js";
    static final String XTERM = "/_static/at/xterm/xterm/6.0.0/lib/xterm.js";
    static final String XTERM_CSS = "/_static/at/xterm/xterm/6.0.0/css/xterm.css";
    static final String FIT = "/_static/at/xterm/addon-fit/0.11.0/lib/addon-fit.js";

    @Test
    void pageServedToAuthenticatedBrowser() {
        given().auth().basic(WebTerminalResource.USER, WebTerminalResource.PASSWORD)
                .when().get(PAGE)
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString(SCRIPT))
                .body(containsString(XTERM))
                .body(containsString("#terminal { position: fixed; inset: 8px; box-sizing: border-box; }"))
                .body(containsString(FIT));
    }

    @Test
    void pageChallengesMissingCredentials() {
        given().when().get(PAGE)
                .then().statusCode(401);
    }

    @Test
    void scriptServedToAuthenticatedBrowser() {
        given().auth().basic(WebTerminalResource.USER, WebTerminalResource.PASSWORD)
                .when().get(SCRIPT)
                .then().statusCode(200)
                .contentType(containsString("javascript"))
                .body(containsString("/tui/ws"));
    }

    @Test
    void scriptChallengesMissingCredentials() {
        given().when().get(SCRIPT)
                .then().statusCode(401);
    }

    @Test
    void xtermLibraryServedFromManagedArtifact() {
        given().when().get(XTERM)
                .then().statusCode(200)
                .contentType(containsString("javascript"))
                .body(containsString("Terminal"));
        given().when().get(FIT)
                .then().statusCode(200)
                .contentType(containsString("javascript"))
                .body(containsString("FitAddon"));
        given().when().get(XTERM_CSS)
                .then().statusCode(200)
                .contentType(containsString("css"));
    }
}
