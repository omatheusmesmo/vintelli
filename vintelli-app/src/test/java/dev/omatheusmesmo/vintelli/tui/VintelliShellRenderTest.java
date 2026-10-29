package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * JVM-level exercise of the offscreen render endpoint; {@link VintelliShellRenderIT}
 * replays the very same assertions against the native runner. Both enable
 * {@code vintelli.inspect.enabled} through the test resource.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
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
