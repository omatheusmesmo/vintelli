package dev.omatheusmesmo.vintelli.capabilities;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;

/**
 * Asserts the capability status surface of the shipped app: the four local
 * capabilities (run, terminal, read-file and the version control provider) are
 * served by the beans of {@code vintelli-local}, the two LSP capabilities
 * (diagnostics and call hierarchy) stay explicitly unavailable until a language
 * server profile is configured, naming the LSP providers behind that state, and
 * the editor bridge stays unavailable until its provider lands. Under
 * {@code -Dnative} the same assertions run against the native runner, so the
 * local and LSP providers also survive AOT.
 */
@QuarkusIntegrationTest
class CapabilitiesResourceIT {

    @TestHTTPResource("/capabilities")
    URI capabilities;

    @Test
    void wrongOrMissingCredentialsDoNotChallengeCapabilities() {
        given().when().get(capabilities).then().statusCode(200);
        given().header("Authorization", "Basic d3Jvbmc6Y3JlZHM=").when().get(capabilities).then().statusCode(200);
    }

    @Test
    void servesTheLocalProvidersAndLeavesTheLspCapabilitiesUnavailableWithoutAProfile() {
        RestAssured.given()
                .when().get(capabilities)
                .then()
                .statusCode(200)
                .body("", hasSize(7))
                .body("providerIdentifier", contains("local", "local", "local", null, null, null, "local"))
                .body("health.state", contains(
                        "AVAILABLE", "AVAILABLE", "AVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE",
                        "AVAILABLE"))
                .body("health.reason", contains(
                        nullValue(), nullValue(), nullValue(),
                        containsString("no healthy provider for 'diagnostics': lsp"),
                        containsString("no healthy provider for 'call-hierarchy': lsp"),
                        containsString("no provider is registered"),
                        nullValue()))
                .body("capability", hasItem("VERSION_CONTROL"));
    }
}
