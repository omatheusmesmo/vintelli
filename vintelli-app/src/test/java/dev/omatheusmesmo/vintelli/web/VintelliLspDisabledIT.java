package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The LSP anchor launches the configured language server and reads the files
 * it is pointed at, so a build without {@code vintelli.lsp.debug-endpoint.enabled}
 * must not serve any of its routes.
 */
@QuarkusIntegrationTest
class VintelliLspDisabledIT {

    @Test
    void servesNothingUnlessTheDebugEndpointIsEnabled() {
        given().queryParam("file", "/etc/hostname").get("/lsp/diagnostics").then()
                .statusCode(404).body("state", equalTo("DISABLED"));
        given().queryParam("file", "/etc/hostname").get("/lsp/hover").then().statusCode(404);
        given().queryParam("file", "/etc/hostname").get("/lsp/symbols").then().statusCode(404);
        given().queryParam("file", "/etc/hostname").get("/lsp/definition").then().statusCode(404);
        given().queryParam("file", "/etc/hostname").get("/lsp/references").then().statusCode(404);
        given().queryParam("file", "/etc/hostname").get("/lsp/completion").then().statusCode(404);
        given().queryParam("file", "/etc/hostname").get("/lsp/callers").then().statusCode(404);
        given().queryParam("file", "/etc/hostname").get("/lsp/callees").then().statusCode(404);
        given().post("/lsp/shutdown").then().statusCode(404);
    }
}
