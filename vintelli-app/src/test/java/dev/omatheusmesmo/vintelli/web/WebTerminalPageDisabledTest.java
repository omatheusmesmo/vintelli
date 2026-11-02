package dev.omatheusmesmo.vintelli.web;

import static io.restassured.RestAssured.given;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * With the browser terminal off (the default) the client page and its script
 * answer 404, with or without credentials, so neither an anonymous client nor
 * a valid account can tell that the page exists while the surface is closed.
 * {@link WebTerminalPageDisabledIT} replays the same assertions on the native
 * runner.
 */
@QuarkusTest
@WithTestResource(value = WebTerminalDisabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalPageDisabledTest {

    @Test
    void pageAnswersNotFoundToAuthenticatedClientWhileDisabled() {
        given().auth().basic(WebTerminalResource.USER, WebTerminalResource.PASSWORD)
                .when().get(WebTerminalPageTest.PAGE)
                .then().statusCode(404);
    }

    @Test
    void scriptAnswersNotFoundToAuthenticatedClientWhileDisabled() {
        given().auth().basic(WebTerminalResource.USER, WebTerminalResource.PASSWORD)
                .when().get(WebTerminalPageTest.SCRIPT)
                .then().statusCode(404);
    }

    @Test
    void anonymousClientAnswersNotFoundWhileDisabled() {
        given().when().get(WebTerminalPageTest.PAGE)
                .then().statusCode(404);
    }
}
