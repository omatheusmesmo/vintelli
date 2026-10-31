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
 * server profile is configured, and the built-in editor bridge remains
 * available. Under {@code -Dnative} the same assertions run against the native
 * runner, so these providers also survive AOT.
 */
@QuarkusIntegrationTest
class CapabilitiesResourceIT {

    @TestHTTPResource("/capabilities")
    URI capabilities;

    @TestHTTPResource("/inspect/editor")
    URI editorInspect;

    @Test
    void wrongOrMissingCredentialsDoNotChallengeCapabilities() {
        given().when().get(capabilities).then().statusCode(200);
        given().header("Authorization", "Basic d3Jvbmc6Y3JlZHM=").when().get(capabilities).then().statusCode(200);
    }

    @Test
    void editorInspectionSurfaceIsDisabledByDefault() {
        given().when().get(editorInspect).then().statusCode(404);
        given().when().post(URI.create(editorInspect + "/insert")).then().statusCode(404);
    }

    @Test
    void servesTheLocalProvidersAndBuiltinEditorAndLeavesLspUnavailableWithoutAProfile() {
        RestAssured.given()
                .when().get(capabilities)
                .then()
                .statusCode(200)
                .body("", hasSize(7))
                .body("providerIdentifier", contains("local", "local", "local", null, null, "builtin", "local"))
                .body("provenance", contains(
                        "local process runner via mvn", "local shell process",
                        "local filesystem, confined to the project root", null, null,
                        "built-in TamboUI TextArea editor", "JGit, repository discovered from the project root"))
                .body("health.state", contains(
                        "AVAILABLE", "AVAILABLE", "AVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "AVAILABLE",
                        "AVAILABLE"))
                .body("health.reason", contains(
                        nullValue(), nullValue(), nullValue(),
                        containsString("no healthy provider for 'diagnostics': lsp"),
                        containsString("no healthy provider for 'call-hierarchy': lsp"),
                        nullValue(),
                        nullValue()))
                .body("capability", hasItem("VERSION_CONTROL"));
    }
}
