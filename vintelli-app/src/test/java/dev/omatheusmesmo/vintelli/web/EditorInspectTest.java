package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises editor operations through the gated headless surface in JVM and native runs. */
@QuarkusTest
@WithTestResource(value = EditorInspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorInspectTest {
    @TestHTTPResource("/inspect/editor")
    URI editor;

    @Test
    void safelyOpensEditsUndoesAndSavesAProjectFile() throws IOException {
        URI open = URI.create(editor + "/open");
        URI insert = URI.create(editor + "/insert");
        URI undo = URI.create(editor + "/undo");
        URI save = URI.create(editor + "/save");
        Path file = EditorInspectEnabledResource.projectRoot().resolve("Editor.java");

        given().queryParam("file", "Editor.java").post(open)
                .then().statusCode(200).body("text", is("class Editor {}\n"), "dirty", is(false));

        given().contentType("application/json").body(Map.of("text", "// temporary\n")).post(insert)
                .then().statusCode(200).body("dirty", is(true), "text", containsString("// temporary\n"));

        given().post(undo)
                .then().statusCode(200).body("text", is("class Editor {}\n"), "dirty", is(false));

        given().contentType("application/json").body(Map.of("text", "// saved\n")).post(insert)
                .then().statusCode(200).body("dirty", is(true));
        given().post(save).then().statusCode(200).body("dirty", is(false));
        assertEquals("// saved\nclass Editor {}\n", Files.readString(file));

        given().queryParam("file", "../pom.xml").post(open).then().statusCode(400);
        assertEquals("// saved\nclass Editor {}\n", Files.readString(file));

        given().contentType("application/json").body(Map.of("text", "// local\n")).post(insert)
                .then().statusCode(200).body("dirty", is(true));
        String externalText = "class ExternalChange {}\n";
        Files.writeString(file, externalText);
        given().post(save).then().statusCode(409).body("error", containsString("changed outside"));
        assertEquals(externalText, Files.readString(file));
    }

    @Test
    void rejectsSymlinksOutsideTheProject() throws IOException {
        Path root = EditorInspectEnabledResource.projectRoot();
        Path outside = Files.createTempFile(root.getParent(), "editor-outside-", ".java");
        Path link = root.resolve("Escape.java");
        try {
            String outsideText = "class Outside {}\n";
            Files.writeString(outside, outsideText);
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                Assumptions.assumeTrue(false, "Symbolic links are unavailable");
            }
            given().queryParam("file", "Escape.java").post(URI.create(editor + "/open"))
                    .then().statusCode(400);
            assertEquals(outsideText, Files.readString(outside));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }
}
