package dev.omatheusmesmo.vintelli.tui;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The inspect render endpoint shows the live shell, so a build without
 * {@code vintelli.inspect.enabled} must not serve it and answers 404 with an
 * empty body.
 */
@QuarkusTest
class VintelliInspectDisabledTest {

    @Test
    void servesNothingUnlessTheInspectEndpointIsEnabled() {
        given().get("/tui/render").then().statusCode(404);
    }
}
