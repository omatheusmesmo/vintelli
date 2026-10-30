package dev.omatheusmesmo.vintelli.acp;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * Contract check against a foreign ACP agent on the native runner. The CI
 * native job installs the agent and sets {@code VINTELLI_FOREIGN_ACP_COMMAND};
 * without that variable the class is skipped.
 */
@QuarkusIntegrationTest
@WithTestResource(ForeignAcpAgentResource.class)
@EnabledIfEnvironmentVariable(named = ForeignAcpAgentResource.COMMAND_VARIABLE, matches = ".+")
class ForeignAcpAgentContractIT {

    @Test
    void opensAndClosesASessionWithTheForeignAgent() {
        given().post("/acp/probe/handshake").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItems("initialize:protocolVersion=1", "session/new:OPENED", "session/close:CLOSED"));
    }

    @Test
    void gatesMcpTransportsAgainstWhatTheForeignAgentAdvertises() {
        given().post("/acp/probe/mcp").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItems("mcp:stdio:ACCEPTED", "mcp:http:ACCEPTED", "mcp:sse:GATED"));
    }
}
