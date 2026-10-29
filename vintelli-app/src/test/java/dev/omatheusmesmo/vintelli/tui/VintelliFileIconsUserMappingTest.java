package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Layers a user mapping file on top of the built-in resource through the
 * {@code vintelli.icons.file} system property and reads the result through
 * {@code /tui/icons}. JVM only: the property is set in the test process, which
 * the native runner of the integration tests does not share, so this class has
 * no integration test sibling.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliFileIconsUserMappingTest {

    private static final String USER_FILE_PROPERTY = "vintelli.icons.file";

    @AfterEach
    void clearUserFile() {
        System.clearProperty(USER_FILE_PROPERTY);
    }

    @Test
    void userKeyInUpperCaseOverridesOnlyTheGlyphOfTheBuiltInEntry(@TempDir Path dir) throws IOException {
        Path userFile = dir.resolve("file-icons.properties");
        Files.writeString(userFile, "NAME.POM.XML.GLYPH=\n");
        System.setProperty(USER_FILE_PROPERTY, userFile.toString());

        given()
                .queryParam("files", "pom.xml", "POM.XML", "Foo.java")
                .when().get("/tui/icons")
                .then()
                .statusCode(200)
                .body("files.'pom.xml'.glyph", is(""))
                .body("files.'pom.xml'.ascii", is("M"))
                .body("files.'POM.XML'.glyph", is(""))
                .body("files.'Foo.java'.glyph", is(""));
    }

    @Test
    void explicitUserFileThatDoesNotExistFailsTheLoadInsteadOfFallingBack(@TempDir Path dir) {
        System.setProperty(USER_FILE_PROPERTY, dir.resolve("absent.properties").toString());

        given().queryParam("files", "pom.xml").when().get("/tui/icons").then().statusCode(500);
    }
}
