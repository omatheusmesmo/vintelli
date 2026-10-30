package dev.omatheusmesmo.vintelli.capabilities;

import static org.hamcrest.Matchers.equalTo;

import java.net.URI;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;

/**
 * Proves in the packaged or native app that the run provider takes its
 * build tool wrapper from the configured project root: a Maven project with
 * both wrapper flavours reports {@code ./mvnw} as the program on every host,
 * and the status row is read from the headless {@code /capabilities} surface.
 */
@QuarkusIntegrationTest
@WithTestResource(value = ProjectWithWrapperResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class LocalRunWrapperIT {

    @TestHTTPResource("/capabilities")
    URI capabilities;

    @Test
    void runProviderDetectsTheMavenWrapperOfTheConfiguredProjectRoot() {
        RestAssured.given()
                .when().get(capabilities)
                .then()
                .statusCode(200)
                .body("find { it.capability == 'RUN' }.providerIdentifier", equalTo("local"))
                .body("find { it.capability == 'RUN' }.provenance",
                        equalTo("local process runner via ./mvnw"));
    }
}
