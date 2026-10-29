package dev.omatheusmesmo.vintelli.capabilities;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;

/**
 * Asserts the capability status surface of the shipped app, where every
 * capability starts with zero providers wired: five rows, each unavailable
 * with a reason. Under {@code -Dnative} the same assertions run against the
 * native runner, so the registry bean and its serialization survive AOT.
 */
@QuarkusIntegrationTest
class CapabilitiesResourceIT {

    @TestHTTPResource("/capabilities")
    URI capabilities;

    @Test
    void reportsEveryCapabilityUnavailableWithReasonWhenNoProviderIsWired() {
        RestAssured.given()
                .when().get(capabilities)
                .then()
                .statusCode(200)
                .body("", hasSize(5))
                .body("providerIdentifier", everyItem(nullValue()))
                .body("health.state", everyItem(is("UNAVAILABLE")))
                .body("health.reason", everyItem(containsString("no provider is registered")));
    }
}
