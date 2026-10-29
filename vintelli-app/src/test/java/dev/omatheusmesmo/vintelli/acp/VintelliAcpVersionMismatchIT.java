package dev.omatheusmesmo.vintelli.acp;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * An agent that answers a protocol version this client does not speak must be
 * refused before any session is opened, on the native runner.
 */
@QuarkusIntegrationTest
@WithTestResource(EchoAcpAgentVersionMismatchResource.class)
class VintelliAcpVersionMismatchIT {

    @Test
    void refusesAnAgentAnsweringAnUnsupportedProtocolVersionBeforeOpeningASession() {
        given().post("/acp/probe/handshake").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItem(startsWith("initialize:REJECTED:agent answered protocol version 2")))
                .body("phases", not(hasItem(startsWith("session/new"))))
                .body("phases", hasItems("client/close:CLOSED"));
    }
}
