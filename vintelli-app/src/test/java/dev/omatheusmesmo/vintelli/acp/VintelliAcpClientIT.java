package dev.omatheusmesmo.vintelli.acp;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Drives the ACP SDK client probe against the packaged artifact (the Mandrel
 * image under {@code -Dnative}) with the stdio echo twin as the agent: the
 * session with every client handler, the MCP transport gate, and the build
 * contract of the SDK for advertised capabilities.
 */
@QuarkusIntegrationTest
@WithTestResource(EchoAcpAgentResource.class)
class VintelliAcpClientIT {

    @Test
    void completesAFullSessionWithThoughtToolCallAndPlanUpdates() {
        given().post("/acp/probe/session").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItem(startsWith("initialize:protocolVersion=1")))
                .body("phases", hasItem("session/new:OPENED"))
                .body("phases", hasItem("session/prompt:end_turn:STREAMED"))
                .body("phases", hasItem("updates:agent_message_chunk,agent_thought_chunk,plan,tool_call,tool_call_update"))
                .body("phases", hasItem(startsWith("permission:selected:")))
                .body("phases", hasItems("agent:result:permission=true",
                        "agent:result:fs-read=hello-from-workspace",
                        "agent:result:fs-outside=DENIED",
                        "agent:result:fs-write=OK",
                        "agent:result:terminal=exit:0,version:true",
                        "agent:result:elicitation-url=accept",
                        "agent:result:elicitation-form=accept:probe",
                        "elicitation:url",
                        "elicitation:form"))
                .body("phases", hasItem("fs/write-on-disk:written-by-agent"))
                .body("phases", hasItem("session/cancel:cancelled"))
                .body("phases", hasItem("session/close:CLOSED"))
                .body("phases", hasItem("client/close:CLOSED"))
                .body("phases", hasItem("terminals:released:1"));
    }

    @Test
    void productionChatServiceStreamsMarkdownToolDiffAndWaitsForPermissionChoice() {
        given().post("/acp/probe/chat-session").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItem("session:new:OPENED"))
                .body("phases", hasItem("permission:pending:write notes.txt"))
                .body("phases", hasItem("message:result:permission=true"))
                .body("phases", hasItem(startsWith("tool:edit_file:completed:--- a/src/Main.java|+++ b/src/Main.java|- old line|+ new line|")))
                .body("phases", hasItem("message:edit streamed"))
                .body("phases", hasItem("session:prompt:COMPLETE"))
                .body("phases", hasItem("session:close:CLOSED"));
    }

    @Test
    void gatesMcpServersByTheTransportsTheAgentAdvertises() {
        given().post("/acp/probe/mcp").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItems("mcp:stdio:ACCEPTED", "mcp:http:ACCEPTED", "mcp:sse:GATED"))
                .body("phases", not(hasItem(startsWith("mcp:sse:REFUSED"))))
                .body("phases", hasItem("client/close:CLOSED"));
    }

    @Test
    void reportsEveryCapabilityAdvertisedWithoutItsHandlerAsAFailedBuild() {
        given().post("/acp/probe/contract").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("phases", hasItem(startsWith("build:read-without-handler:REJECTED")))
                .body("phases", hasItem(startsWith("build:write-without-handler:REJECTED")))
                .body("phases", hasItem(startsWith("build:terminal-without-handler:REJECTED")))
                .body("phases", hasItem(startsWith("build:elicitation-without-handler:REJECTED")))
                .body("phases", hasItem("build:no-capabilities:OK"));
    }
}
