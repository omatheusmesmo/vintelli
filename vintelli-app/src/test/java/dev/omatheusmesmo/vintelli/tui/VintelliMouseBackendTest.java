package dev.omatheusmesmo.vintelli.tui;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import dev.omatheusmesmo.vintelli.tui.terminal.TrackingJLineBackendProvider;
import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies through the gated inspection surface that {@code vintelli-jline3}
 * is discovered via {@code ServiceLoader} and drives JLine {@code trackMouse}
 * transitions. {@link VintelliMouseBackendIT} replays the same assertions
 * against the packaged artifact (including the native image).
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliMouseBackendTest {

    @Test
    void discoversVintelliJLine3AndDrivesTrackMouseTransitions() {
        given()
                .when().get("/tui/mouse-backend")
                .then()
                .statusCode(200)
                .body("defaultBackend", is(TrackingJLineBackendProvider.NAME))
                .body("discovered", is(true))
                .body("providerClass", is(TrackingJLineBackendProvider.class.getName()))
                .body("providers", hasItem(TrackingJLineBackendProvider.NAME))
                .body("transitions", contains("Button", "Any", "Off", "Any", "Off"));
    }
}

