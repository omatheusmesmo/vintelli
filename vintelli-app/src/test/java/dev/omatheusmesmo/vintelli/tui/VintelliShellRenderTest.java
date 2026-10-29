package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * JVM-level exercise of the offscreen render endpoint; {@link VintelliShellRenderIT}
 * replays the very same assertions against the native runner.
 */
@QuarkusTest
class VintelliShellRenderTest {

    @Test
    void exposesToolWindowTabsAndStatusFromAnOffscreenFrame() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("tabRow", containsString("1. Run"),
                        "tabRow", containsString("4. ACP Agents"),
                        "mainAreaTop", containsString("Project"),
                        "mainAreaTop", containsString("Editor"),
                        "mainAreaTop", containsString("╭"),
                        "status", containsString("Vintelli ready"));
    }
}
