package dev.omatheusmesmo.vintelli.capabilities;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;

/**
 * Asserts the capability status surface of the shipped app: the three local
 * capabilities are served by the beans of {@code vintelli-local} and the three
 * remaining capabilities (diagnostics, call hierarchy and the editor bridge)
 * stay explicitly unavailable until their providers land. Under
 * {@code -Dnative} the same assertions run against the native runner, so the
 * local providers also survive AOT.
 */
@QuarkusIntegrationTest
class CapabilitiesResourceIT {

    @TestHTTPResource("/capabilities")
    URI capabilities;

    @Test
    void servesTheLocalProvidersAndKeepsTheRemoteCapabilitiesUnavailable() {
        RestAssured.given()
                .when().get(capabilities)
                .then()
                .statusCode(200)
                .body("", hasSize(6))
                .body("providerIdentifier", contains("local", "local", "local", null, null, null))
                .body("health.state", contains("AVAILABLE", "AVAILABLE", "AVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"))
                .body("health.reason", contains(
                        nullValue(), nullValue(), nullValue(),
                        containsString("no provider is registered"),
                        containsString("no provider is registered"),
                        containsString("no provider is registered")));
    }
}
