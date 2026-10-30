package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliProcessProbeTest {

    @Test
    void runsTheFixedProcessProbe() {
        given()
                .when().post("/tui/process-probe")
                .then()
                .statusCode(200)
                .body("successOutput", containsString("vintelli-process-probe-ok"))
                .body("timeoutOutput", containsString("vintelli-process-probe-timeout"))
                .body("refusal", containsString("outside the project root"))
                .body("detail", is(""));
    }
}
