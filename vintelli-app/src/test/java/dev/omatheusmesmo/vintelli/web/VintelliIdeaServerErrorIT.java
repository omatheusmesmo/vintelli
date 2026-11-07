package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.idea.FakeIdeaMcpServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * An IDEA that answers with an HTTP server error is reachable and must be
 * classified as {@code ERROR}, never as {@code ENDPOINT_NOT_FOUND}, which is
 * reserved for a 404. Driven through the HTTP anchor against the packaged
 * artifact, with the IDEA server replaced by the in-process twin.
 */
@QuarkusIntegrationTest
@WithTestResource(IdeaMcpTwinResource.class)
class VintelliIdeaServerErrorIT {

    private FakeIdeaMcpServer twin;

    @BeforeEach
    void connectToAHealthyTwin() throws IOException {
        twin = IdeaMcpTwinResource.twin();
        twin.behaveNormally();
        twin.restart();
        given().post("/idea/reconnect").then().statusCode(200).body("state", equalTo("CONNECTED"));
    }

    @Test
    void classifiesAnIdeaServerErrorOf500AsErrorNotAsAMissingEndpoint() {
        twin.rejectWith(500);

        given().post("/idea/reconnect").then()
                .statusCode(502)
                .body("state", equalTo("ERROR"))
                .body("detail", containsString("500"))
                .body("detail", not(containsString("response body")));
    }

    @Test
    void classifiesAnIdeaServerErrorOf503AsErrorNotAsAMissingEndpoint() {
        twin.rejectWith(503);

        given().post("/idea/reconnect").then()
                .statusCode(502)
                .body("state", equalTo("ERROR"))
                .body("detail", containsString("503"))
                .body("detail", not(containsString("response body")));
    }
}
