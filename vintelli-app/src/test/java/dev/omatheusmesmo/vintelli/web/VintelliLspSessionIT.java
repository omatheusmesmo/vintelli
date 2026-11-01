package dev.omatheusmesmo.vintelli.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Drives a full language server session through the HTTP anchor against the
 * fake server launched by the regular profile command: initialize, a
 * diagnostics round trip, hover and an explicit shutdown with exit. Under
 * {@code -Dnative} the same assertions run against the native runner, which
 * proves the registration build step keeps LSP4J working there.
 */
@QuarkusIntegrationTest
@WithTestResource(LspTwinResource.class)
class VintelliLspSessionIT {

    @Test
    void theCapabilityRegistryServesTheLspPortsThroughTheProfile() {
        given().get("/capabilities").then()
                .statusCode(200)
                .body("providerIdentifier", hasItem("twin"))
                .body("provenance", hasItem(containsString("twin over stdio")));
    }

    @Test
    void diagnosticsRoundTripReceivesThePushedProblems() throws IOException {
        Files.writeString(LspTwinResource.document(), "class Fake {}");

        given().queryParam("file", LspTwinResource.document().toAbsolutePath().toString())
                .get("/lsp/diagnostics")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", containsString("fake problem"));

        assertFalse(LspTwinResource.trace().isEmpty());
        assertTrue(LspTwinResource.trace().contains("initialize\n"), LspTwinResource.trace());
        assertTrue(LspTwinResource.trace().contains("didOpen\n"), LspTwinResource.trace());
    }

    @Test
    void hoverAnswersOverTheSession() {
        given().queryParam("file", LspTwinResource.document().toAbsolutePath().toString())
                .queryParam("line", 0)
                .queryParam("column", 0)
                .get("/lsp/hover")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", containsString("# fake hover twin-marker"));
    }

    @Test
    void documentSymbolsAreAvailableThroughTheNavigationPort() throws IOException {
        Files.writeString(LspTwinResource.document(), "class Fake {}\n");

        given().queryParam("file", LspTwinResource.document().toAbsolutePath().toString())
                .get("/lsp/symbols")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", containsString("FakeClass"))
                .body("text", containsString("  fakeMethod"));

        assertTrue(LspTwinResource.trace().contains("documentSymbol\n"), LspTwinResource.trace());
    }

    @Test
    void shutdownReachesTheServerAndEndsTheProcess() {
        given().queryParam("file", LspTwinResource.document().toAbsolutePath().toString())
                .get("/lsp/hover")
                .then()
                .statusCode(200);

        given().post("/lsp/shutdown").then()
                .statusCode(200)
                .body("state", equalTo("SHUTDOWN"));

        assertTrue(LspTwinResource.trace().contains("shutdown\n"), LspTwinResource.trace());
        assertTrue(LspTwinResource.trace().contains("exit\n"), LspTwinResource.trace());
    }

    @Test
    void foreignHostAndForeignOriginNeverReachTheLanguageServer() {
        String file = LspTwinResource.document().toAbsolutePath().toString();
        int shutdownsBefore = occurrences(LspTwinResource.trace(), "shutdown\n");

        given().headers("Host", "rebound.example.org").queryParam("file", file).get("/lsp/hover")
                .then().statusCode(403);
        given().headers("Origin", "https://rebound.example.org").queryParam("file", file).get("/lsp/diagnostics")
                .then().statusCode(403);
        given().headers("Host", "rebound.example.org").post("/lsp/shutdown")
                .then().statusCode(403);
        given().headers("Origin", "https://rebound.example.org").post("/lsp/shutdown")
                .then().statusCode(403);

        assertEquals(shutdownsBefore, occurrences(LspTwinResource.trace(), "shutdown\n"),
                LspTwinResource.trace());
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int from = text.indexOf(needle); from >= 0; from = text.indexOf(needle, from + 1)) {
            count++;
        }
        return count;
    }
}
