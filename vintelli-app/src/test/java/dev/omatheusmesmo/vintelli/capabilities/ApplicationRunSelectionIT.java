package dev.omatheusmesmo.vintelli.capabilities;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@WithTestResource(value = ApplicationRunProjectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ApplicationRunSelectionIT {

    @Test
    void applicationStartsAreDetectedAndTheSelectedNameIsSaved() {
        given().get("/tui/run-selection").then().statusCode(200)
                .body("configurations", hasItems("maven:quarkus:dev", "maven:spring-boot:run", "maven:compile"))
                .body("selected", equalTo("maven:quarkus:dev"));
        given().queryParam("id", "maven:spring-boot:run").post("/tui/run-selection").then().statusCode(200)
                .body("selected", equalTo("maven:spring-boot:run"));
        given().get("/tui/run-selection").then().statusCode(200).body("selected", equalTo("maven:spring-boot:run"));
        given().queryParam("id", "unknown").post("/tui/run-selection").then().statusCode(400);
        given().post("/tui/run-selection").then().statusCode(400);
        given().get("/tui/run-selection").then().statusCode(200).body("selected", equalTo("maven:spring-boot:run"));
    }
}
