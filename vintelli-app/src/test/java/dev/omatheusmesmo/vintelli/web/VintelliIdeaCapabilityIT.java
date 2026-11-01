package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.idea.FakeIdeaMcpServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusIntegrationTest
@WithTestResource(IdeaMcpTwinResource.class)
class VintelliIdeaCapabilityIT {

    private FakeIdeaMcpServer twin;

    @BeforeEach
    void connectToAHealthyTwin() throws Exception {
        twin = IdeaMcpTwinResource.twin();
        twin.behaveNormally();
        twin.restart();
        given().post("/idea/reconnect").then().statusCode(200);
    }

    @Test
    void runCapabilityReportsIdeaProviderWhenTwinIsHealthy() {
        given().get("/capabilities").then()
                .statusCode(200)
                .body("find { it.capability == 'RUN' }.providerIdentifier", equalTo("idea"));
    }

    @Test
    void diagnosticsCapabilityReportsIdeaProviderWhenTwinIsHealthy() {
        given().get("/capabilities").then()
                .statusCode(200)
                .body("find { it.capability == 'DIAGNOSTICS' }.providerIdentifier", equalTo("idea"));
    }

    @Test
    void terminalCapabilityReportsIdeaProviderWhenTwinIsHealthy() {
        given().get("/capabilities").then()
                .statusCode(200)
                .body("find { it.capability == 'TERMINAL' }.providerIdentifier", equalTo("idea"));
    }

    @Test
    void twinExposesRunAndTerminalTools() {
        given().get("/idea/tools").then()
                .statusCode(200)
                .body("text", containsString("get_run_configurations"))
                .body("text", containsString("execute_terminal_command"));
    }

    @Test
    void diagnosticsToolReturnsProblemsFromTwin() {
        given().queryParam("name", "get_file_problems").get("/idea/tool").then()
                .statusCode(200)
                .body("state", equalTo("CONNECTED"))
                .body("text", containsString("cannot find symbol"));
    }
}
