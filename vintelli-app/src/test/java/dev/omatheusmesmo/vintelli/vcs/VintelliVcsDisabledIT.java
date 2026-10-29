package dev.omatheusmesmo.vintelli.vcs;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The VCS anchor serves the Git state of the project to any loopback caller,
 * so a build without {@code vintelli.vcs.debug-endpoint.enabled} must not
 * serve it.
 */
@QuarkusIntegrationTest
class VintelliVcsDisabledIT {

    @Test
    void servesNothingUnlessTheDebugEndpointIsEnabled() {
        given().get("/vcs/status").then().statusCode(404).body("state", equalTo("DISABLED"));
        given().get("/vcs/diff").then().statusCode(404);
    }
}
