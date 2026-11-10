package dev.vintelli.spike;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(DebuggeeResource.class)
class JdiProbeTest {
    @Test void attachesStopsAtBreakpointStepsAndReadsVariables() {
        given().get("/probe").then().statusCode(200).body("function", equalTo("checkpoint"))
                .body("value", equalTo(21)).body("doubled", equalTo(42)).body("threads", hasItem("main"));
    }
}
