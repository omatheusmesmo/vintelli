package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.ExternalLibrariesResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@WithTestResource(value = ExternalLibrariesResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliExternalLibrariesTest {

    @Test
    void exposesDirectDependenciesAndIncompleteOfflineMetadata() {
        given()
                .when().get("/tui/dependencies")
                .then()
                .statusCode(200)
                .body("complete", is(false),
                        "modules[0].dependencies[0].artifactId", is("offline-fixture"),
                        "modules[0].dependencies[0].version", is("1.0"),
                        "modules[0].dependencies[0].scope", is("test"),
                        "modules[0].dependencies[0].disposition", is("MISSING"));
    }
}
