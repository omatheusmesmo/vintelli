package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.idea.FakeIdeaMcpServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the IDEA MCP client through its HTTP anchor against the packaged
 * artifact (the Mandrel image under {@code -Dnative}). The IDEA server is
 * replaced by an in-process twin on an ephemeral port that is switched
 * between healthy and failing behaviors; every failure mode must come back as
 * a classified HTTP status.
 */
@QuarkusIntegrationTest
@WithTestResource(IdeaMcpTwinResource.class)
class VintelliIdeaClientIT {

    private FakeIdeaMcpServer twin;

    @BeforeEach
    void connectToAHealthyTwin() throws IOException {
        twin = IdeaMcpTwinResource.twin();
        twin.behaveNormally();
        twin.restart();
        given().post("/idea/reconnect").then().statusCode(200).body("state", equalTo("CONNECTED"));
    }

    @Test
    void connectsAndListsIdeaTools() {
        given().get("/idea/tools").then()
                .statusCode(200)
                .body("state", equalTo("CONNECTED"))
                .body("text", containsString("get_run_configurations"))
                .body("text", containsString("execute_terminal_command"));
    }

    @Test
    void callsAReadOnlyToolAndReturnsItsOutput() {
        given().queryParam("name", "get_run_configurations").get("/idea/tool").then()
                .statusCode(200)
                .body("state", equalTo("CONNECTED"))
                .body("text", containsString("Vintelli"));
    }

    @Test
    void reportsAToolLevelErrorWithoutLosingTheConnection() {
        given().queryParam("name", FakeIdeaMcpServer.ERROR_TOOL).get("/idea/tool").then()
                .statusCode(422)
                .body("state", equalTo("TOOL_ERROR"))
                .body("text", equalTo("IDEA refused the tool"));

        given().get("/idea/tools").then().statusCode(200).body("state", equalTo("CONNECTED"));
    }

    @Test
    void classifiesAMissingTokenAs401() {
        twin.rejectWith(401);

        given().post("/idea/reconnect").then()
                .statusCode(401)
                .body("state", equalTo("AUTH_REQUIRED"));
    }

    @Test
    void classifiesAPendingConsentAs403() {
        twin.rejectWith(403);

        given().post("/idea/reconnect").then()
                .statusCode(403)
                .body("state", equalTo("CONSENT_PENDING"));
    }

    @Test
    void classifiesAWrongEndpointAs404() {
        twin.rejectWith(404);

        given().post("/idea/reconnect").then()
                .statusCode(404)
                .body("state", equalTo("ENDPOINT_NOT_FOUND"));
    }

    @Test
    void classifiesAnIdeaThatWentDownAs503() {
        twin.stop();

        given().queryParam("name", "get_run_configurations").get("/idea/tool").then()
                .statusCode(503)
                .body("state", equalTo("UNREACHABLE"));
        given().post("/idea/reconnect").then()
                .statusCode(503)
                .body("state", equalTo("UNREACHABLE"));
    }

    @Test
    void classifiesAToolCallThatOutlastsItsTimeoutAs504() {
        twin.delayToolCalls(Duration.ofSeconds(3));

        given().queryParam("name", "get_run_configurations").queryParam("timeoutMillis", 300)
                .get("/idea/tool").then()
                .statusCode(504)
                .body("state", equalTo("TIMEOUT"));
    }

    @Test
    void classifiesAMalformedToolResponseAs502() {
        given().queryParam("name", FakeIdeaMcpServer.MALFORMED_TOOL).get("/idea/tool").then()
                .statusCode(502)
                .body("state", equalTo("ERROR"));
    }

    @Test
    void refusesToolsThatAreNotReadOnlyWithoutReachingIdea() {
        int requestsBefore = twin.paths().size();

        given().queryParam("name", "execute_terminal_command").get("/idea/tool").then()
                .statusCode(400)
                .body("state", equalTo("REJECTED"));
        given().get("/idea/tool").then().statusCode(400);

        assertEquals(requestsBefore, twin.paths().size());
    }
}
