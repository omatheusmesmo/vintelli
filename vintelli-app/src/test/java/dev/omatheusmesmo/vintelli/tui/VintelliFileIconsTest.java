package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/**
 * Resolves the file type icons through the inspection surface; the inherited
 * {@link VintelliFileIconsIT} replays the very same assertions on the native
 * runner, where a {@code file-icons.properties} the image does not package
 * fails the endpoint load instead of staying silent. Both enable
 * {@code vintelli.inspect.enabled} through the test resource. The requested
 * names come straight from the mapping (exact name matches, an extension
 * match and one unmatched name that falls back to the default file icon), and
 * the absence of a user mapping file in the test environment leaves every
 * answer on the built-in mapping.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliFileIconsTest {

    @Test
    void resolvesBuiltInGlyphAndAsciiFacesForTheRequestedNames() {
        given()
                .queryParam("files", "Foo.java", "pom.xml", "README.md", "build.gradle", "x.xml", "report.zzz")
                .when().get("/tui/icons")
                .then()
                .statusCode(200)
                .body("mode", anyOf(is("nerd"), is("ascii")))
                .body("files.'Foo.java'.glyph", is("\uE738"))
                .body("files.'Foo.java'.ascii", is("J"))
                .body("files.'pom.xml'.glyph", is("\uE82C"))
                .body("files.'pom.xml'.ascii", is("M"))
                .body("files.'README.md'.glyph", is("\uE609"))
                .body("files.'README.md'.ascii", is("M"))
                .body("files.'build.gradle'.glyph", is("\uE660"))
                .body("files.'build.gradle'.ascii", is("G"))
                .body("files.'x.xml'.glyph", is("\uE619"))
                .body("files.'x.xml'.ascii", is("X"))
                .body("files.'report.zzz'.glyph", is("\uF15B"))
                .body("files.'report.zzz'.ascii", is("*"));
    }
}
