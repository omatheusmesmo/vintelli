package dev.omatheusmesmo.vintelli.tui;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The inspect endpoints show the live shell and can switch its theme, so a
 * build without {@code vintelli.inspect.enabled} must not serve any of them
 * and answers 404 with an empty body.
 */
@QuarkusTest
class VintelliInspectDisabledTest {

    @Test
    void servesNothingUnlessTheInspectEndpointIsEnabled() {
        given().get("/tui/render").then().statusCode(404);
        given().post("/tui/theme?name=darcula").then().statusCode(404);
        given().get("/tui/keymap").then().statusCode(404);
    }

    /**
     * The icon inspection loads the icon mapping, so the same gate applies
     * without {@code vintelli.inspect.enabled}; {@link VintelliInspectDisabledIT}
     * replays it against the packaged artifact.
     */
    @Test
    void answers404ForIconsUnlessTheInspectEndpointIsEnabled() {
        given().get("/tui/icons").then().statusCode(404);
    }

    @Test
    void answers404ForProcessProbeUnlessTheInspectEndpointIsEnabled() {
        given().post("/tui/process-probe").then().statusCode(404);
    }
}
