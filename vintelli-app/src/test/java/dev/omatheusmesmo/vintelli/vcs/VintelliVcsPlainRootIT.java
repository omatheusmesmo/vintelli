package dev.omatheusmesmo.vintelli.vcs;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Asserts the explicit unavailability of a project root that is not inside a
 * Git repository, on the packaged artifact (the native runner under
 * {@code -Dnative}) included: the registry selection has no healthy provider,
 * so the HTTP anchor answers 503 with the reason instead of status data.
 */
@QuarkusIntegrationTest
@WithTestResource(TemporaryPlainDirectoryResource.class)
class VintelliVcsPlainRootIT {

    @Test
    void reportsTheExplicitNotARepositoryUnavailability() {
        given().get("/vcs/status").then()
                .statusCode(503)
                .body("state", equalTo("UNAVAILABLE"))
                .body("detail", containsString("Not a Git repository"));
    }
}
