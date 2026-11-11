package dev.omatheusmesmo.vintelli.acp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@WithTestResource(SelectableAcpAgentResource.class)
class VintelliAcpAgentPickerIT {

    @Test
    void selectedAgentStartsAndStreamsThroughTheProductionService() {
        given().queryParam("agent", "opencode").post("/acp/probe/chat-session").then()
                .statusCode(200).body("state", equalTo("OK"))
                .body("phases", hasItem("session:prompt:COMPLETE"))
                .body("phases", hasItem("message:result:permission=true"))
                .body("phases", hasItem("session:close:CLOSED"));
    }

    @Test
    void missingExecutableAndUnknownAgentFailBeforeOpeningASession() {
        given().queryParam("agent", "pi").post("/acp/probe/chat-session").then()
                .statusCode(200).body("state", equalTo("ERROR"))
                .body("detail", containsString("Executable not found: vintelli-missing-agent-executable"));
        given().queryParam("agent", "unknown").post("/acp/probe/chat-session").then()
                .statusCode(200).body("state", equalTo("ERROR"))
                .body("detail", containsString("Unknown ACP agent: unknown"));
    }
}
