package dev.omatheusmesmo.vintelli.acp;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The probe endpoint spawns whatever agent is configured, so a build without
 * {@code vintelli.acp.probe.enabled} must not serve it.
 */
@QuarkusIntegrationTest
class VintelliAcpProbeDisabledIT {

    @Test
    void servesNothingUnlessTheProbeIsEnabled() {
        given().post("/acp/probe/session").then().statusCode(404).body("state", equalTo("DISABLED"));
        given().post("/acp/probe/mcp").then().statusCode(404);
    }
}
