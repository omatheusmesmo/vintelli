package dev.vintelli.spike;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(DapTwinResource.class)
class DapProbeTest {
    @Test void typedRequestsResponsesAndCallbacksCrossTheWire() {
        given().get("/probe").then().statusCode(200).body("configurationDone", equalTo(true))
                .body("reason", equalTo("breakpoint")).body("threads", hasItem("main"));
    }
}
