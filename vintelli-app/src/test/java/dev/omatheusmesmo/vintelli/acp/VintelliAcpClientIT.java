package dev.omatheusmesmo.vintelli.acp;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * Drives the ACP SDK client probe against the packaged artifact (the Mandrel
 * image under {@code -Dnative}): a full session over stdio against the echo
 * agent and the refusal of a session request that carries an MCP server.
 */
@QuarkusIntegrationTest
@WithTestResource(EchoAcpAgentResource.class)
class VintelliAcpClientIT {

    @Test
    void completesAFullSession() {
        given().post("/acp/probe/session").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItem(startsWith("initialize:protocolVersion=")))
                .body("phases", hasItem("session/new:OPENED"))
                .body("phases", hasItem("session/prompt:end_turn:STREAMED"))
                .body("phases", hasItem("session/cancel:cancelled"))
                .body("phases", hasItem("session/close:CLOSED"))
                .body("phases", hasItem("client/close:CLOSED"));
    }

    @Test
    void agentRefusalOfAnMcpServerSurfacesAsAnAcpError() {
        given().post("/acp/probe/mcp").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItem(startsWith("mcp-session:REFUSED:code=")))
                .body("phases", hasItem("client/close:CLOSED"));
    }
}
