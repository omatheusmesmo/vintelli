package dev.omatheusmesmo.vintelli.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Asks the fake language server for code actions through the headless anchor.
 * The start line of the requested range selects the twin's answer: a quick fix
 * with a bare command, an empty list, an error response, an edit without a range
 * and a file creation. Each failure mode must end in an explicit state while the
 * session keeps serving afterwards.
 */
@QuarkusIntegrationTest
@WithTestResource(LspTwinResource.class)
class VintelliLspCodeActionIT {

    /**
     * Every request reads the document from disk, so each test writes it itself
     * instead of depending on the method order.
     */
    @BeforeEach
    void writeDocument() throws IOException {
        Files.writeString(LspTwinResource.document(), "class Fake {}\n");
    }

    @Test
    void aQuickFixAndACommandReachTheHeadlessSurface() {
        given().queryParam("file", document()).queryParam("line", 0).queryParam("column", 0)
                .get("/lsp/code-actions")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", containsString("Add missing import java.util.List [quickfix] preferred edits=1 unsupported=0"))
                .body("text", containsString("Run the fake fixer [command] command=fake.fixer"));

        assertTrue(LspTwinResource.trace().contains("codeAction\n"), LspTwinResource.trace());
    }

    @Test
    void anEmptyAnswerIsOkWithoutItems() {
        given().queryParam("file", document()).queryParam("line", 1).queryParam("column", 0)
                .get("/lsp/code-actions")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", emptyString());
    }

    @Test
    void anErrorResponseIsReportedAsUnavailable() {
        given().queryParam("file", document()).queryParam("line", 2).queryParam("column", 0)
                .get("/lsp/code-actions")
                .then()
                .statusCode(503)
                .body("state", equalTo("UNAVAILABLE"))
                .body("detail", containsString("the fake server cannot compute fixes"));
    }

    @Test
    void anEditWithoutARangeIsReportedAsMalformed() {
        given().queryParam("file", document()).queryParam("line", 3).queryParam("column", 0)
                .get("/lsp/code-actions")
                .then()
                .statusCode(503)
                .body("state", equalTo("MALFORMED"))
                .body("detail", containsString("a text edit has no range or no new text"));
    }

    @Test
    void aFileCreationIsListedAsAnUnsupportedChange() {
        given().queryParam("file", document()).queryParam("line", 4).queryParam("column", 0)
                .get("/lsp/code-actions")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", containsString("Create the missing class [quickfix] edits=0 unsupported=1"));
    }

    @Test
    void theSessionKeepsAnsweringAfterAFailedRequest() {
        given().queryParam("file", document()).queryParam("line", 2).queryParam("column", 0)
                .get("/lsp/code-actions")
                .then()
                .statusCode(503);

        given().queryParam("file", document()).queryParam("line", 0).queryParam("column", 0)
                .get("/lsp/code-actions")
                .then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", containsString("Add missing import java.util.List"));
    }

    private static String document() {
        return LspTwinResource.document().toAbsolutePath().toString();
    }
}
