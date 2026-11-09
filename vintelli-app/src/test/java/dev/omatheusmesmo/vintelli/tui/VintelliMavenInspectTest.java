package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Reads the pom of the working directory through the inspection surface, the
 * way the Maven tool window does; the inherited {@link VintelliMavenInspectIT}
 * replays the very same assertions on the native runner, where a JDK XML
 * parser the image cannot start fails the endpoint instead of staying silent.
 * The test runs in the {@code vintelli-app} module, whose pom has no child
 * modules, so the tree is the root alone, with the empty root selector.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliMavenInspectTest {

    @Test
    void readsThePomOfTheProjectRootWithTheXmlParser() {
        given()
                .when().get("/tui/maven")
                .then()
                .statusCode(200)
                .body("state", is("loaded"),
                        "selectors", hasItem(""));
    }
}
