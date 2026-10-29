package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * JVM-level exercise of the offscreen render endpoint; {@link VintelliShellRenderIT}
 * replays the very same assertions against the native runner. Both enable
 * {@code vintelli.inspect.enabled} through the test resource.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliShellRenderTest {

    @Test
    void exposesToolWindowTabsAndStatusFromAnOffscreenFrame() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("tabRow", containsString("1. Run"),
                        "tabRow", containsString("4. ACP Agents"),
                        "mainAreaTop", containsString("Project"),
                        "mainAreaTop", containsString("Editor"),
                        "mainAreaTop", containsString("╭"),
                        "status", containsString("Vintelli ready"),
                        "theme", is(ShellThemes.DEFAULT_THEME));
    }

    /**
     * Loads the TamboUI binding presets through the inspection surface; the
     * inherited IT replays this on the native runner, where a missing built-in
     * bindings resource fails this call instead of staying silent.
     */
    @Test
    void exposesTheKeymapWithTheActionResultChords() {
        given()
                .when().get("/tui/keymap")
                .then()
                .statusCode(200)
                .body("source", is("intellij"),
                        "shortcuts.quit", containsString("Ctrl+c"),
                        "shortcuts.SearchEverywhere", equalTo("Ctrl+p"),
                        "shortcuts.ActivateRunToolWindow", containsString("Alt+1"));
    }

    /**
     * The runtime theme switch through the gated inspect surface: the switch
     * returns the frame it painted, and the render endpoint reports the new
     * theme afterwards, so the stylesheet swap, its validation and the next
     * full render are all exercised. The native runner replays this without a
     * restart. The default theme is restored, so the endpoint stays
     * independent of the order the tests run in.
     */
    @Test
    void switchesTheThemeByNameAndPaintsTheNextFrameInTheNewTheme() {
        try {
            given()
                    .when().post("/tui/theme?name=darcula")
                    .then()
                    .statusCode(200)
                    .body("theme", is("darcula"),
                            "tabRow", containsString("Run"),
                            "mainAreaTop", containsString("Project"));
            given()
                    .when().get("/tui/render")
                    .then()
                    .statusCode(200)
                    .body("theme", is("darcula"),
                            "status", containsString("Vintelli ready"));
        } finally {
            given()
                    .when().post("/tui/theme?name=" + ShellThemes.DEFAULT_THEME)
                    .then()
                    .statusCode(200)
                    .body("theme", is(ShellThemes.DEFAULT_THEME));
        }
    }

    /**
     * An unknown name fails like the startup validation does, and the active
     * theme survives the failed call: the next render still reports the theme
     * the test sequence left active.
     */
    @Test
    void rejectsAnUnknownThemeNameAndKeepsTheActiveTheme() {
        given()
                .when().post("/tui/theme?name=bogus")
                .then()
                .statusCode(400)
                .body("error", containsString("unknown shell theme 'bogus'; available themes:"));
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("theme", is(ShellThemes.DEFAULT_THEME));
    }
}
