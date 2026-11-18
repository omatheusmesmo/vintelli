package dev.omatheusmesmo.vintelli.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.lsp.FakeLspServer;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Asks the fake language server for completions through the headless anchor. The twin answers the rich list at
 * its marked line with the items out of order: the reply keeps the server's sort order, and each detail is
 * shown in brackets. The request reads the document from disk, so each test writes it itself.
 */
@QuarkusIntegrationTest
@WithTestResource(LspTwinResource.class)
class VintelliLspCompletionIT {

    @BeforeEach
    void writeDocument() throws IOException {
        Files.writeString(LspTwinResource.document(), "class Fake {}\n");
    }

    @Test
    void completionsReachTheHeadlessSurfaceInTheServerSortOrder() {
        given().queryParam("file", document()).queryParam("line", FakeLspServer.RICH_COMPLETION_LINE)
                .queryParam("column", 0)
                .get("/lsp/completion")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", equalTo("alpha ((value: String))\nbeta\ngamma\nzeta (int)"));

        assertTrue(LspTwinResource.trace().contains("completion\n"), LspTwinResource.trace());
    }

    @Test
    void aPlainCompletionKeepsTheTwoItemsOfTheSession() {
        given().queryParam("file", document()).queryParam("line", 0).queryParam("column", 0)
                .get("/lsp/completion")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", equalTo("fake_completion_" + LspTwinResource.MARKER + "\nfake_second"));
    }

    private static String document() {
        return LspTwinResource.document().toAbsolutePath().toString();
    }
}
