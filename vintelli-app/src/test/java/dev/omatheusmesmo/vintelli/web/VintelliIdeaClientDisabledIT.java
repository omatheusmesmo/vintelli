package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The IDEA anchor drives the user's IDE with the configured token, so a build
 * without {@code vintelli.idea.debug-endpoint.enabled} must not serve it.
 */
@QuarkusIntegrationTest
class VintelliIdeaClientDisabledIT {

    @Test
    void servesNothingUnlessTheDebugEndpointIsEnabled() {
        given().get("/idea/tools").then().statusCode(404).body("state", equalTo("DISABLED"));
        given().queryParam("name", "get_run_configurations").get("/idea/tool").then().statusCode(404);
        given().post("/idea/reconnect").then().statusCode(404);
    }
}
