package dev.omatheusmesmo.vintelli.idea;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
public class VintelliIdeaClientTest {

    private static Process fakeIdea;

    @BeforeAll
    static void startFakeIdea() throws Exception {
        fakeIdea = IdeaMcpServerHelper.startServer();
    }

    @AfterAll
    static void stopFakeIdea() {
        if (fakeIdea != null && fakeIdea.isAlive()) {
            fakeIdea.destroyForcibly();
        }
    }

    @Test
    public void exposesIdeaToolsThroughTheDebugAnchor() {
        given().get("/idea/tools").then()
                .body(containsString("get_run_configurations"),
                        containsString("execute_run_configuration"),
                        containsString("execute_terminal_command"),
                        containsString("get_diagnostics"));
    }

    @Test
    public void callsAnIdeaToolThroughTheDebugAnchor() {
        given()
                .queryParam("name", "execute_run_configuration")
                .get("/idea/tool").then()
                .body(containsString("handled tool execute_run_configuration"));
    }
}
