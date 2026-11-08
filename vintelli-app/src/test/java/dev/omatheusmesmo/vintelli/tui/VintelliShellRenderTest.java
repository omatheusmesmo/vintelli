package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;

/**
 * JVM-level exercise of the offscreen render endpoint; {@link VintelliShellRenderIT}
 * replays the very same assertions against the native runner. Both enable
 * {@code vintelli.inspect.enabled} through the test resource.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliShellRenderTest {

    /**
     * The project pane footer carries the READ_FILE state, which the shell reads on a virtual thread after
     * construction. The poll waits until the placeholder {@code checking} text is replaced by the settled state,
     * so the assertion observes the asynchronous read and not the first frame.
     */
    @Test
    void showsTheSettledReadFileStateInTheProjectPaneOfTheRenderedFrame() throws InterruptedException {
        String mainArea = awaitSettledMainArea();
        assertTrue(mainArea.contains("Read File") && !mainArea.contains("checking"),
                "the READ_FILE state never settled in the main area:\n" + mainArea);
    }

    /**
     * The project pane runs down to the row above the status bar, so its footer
     * sits on the last main area row, left of the editor, while the bottom tool
     * windows start at the editor's left edge on the tab row of the same frame.
     */
    @Test
    void showsTheBottomToolWindowsBesideTheProjectPaneFooter() throws InterruptedException {
        String mainArea = awaitSettledMainArea();
        String[] rows = mainArea.split("\n", -1);
        String footer = rows[rows.length - 1];
        String tabRow = Arrays.stream(rows).filter(row -> row.contains("1 Run")).findFirst().orElse("");
        int editorTabColumn = tabRow.indexOf("1 Run");
        String footerLeftOfTabs = footer.length() > editorTabColumn ? footer.substring(0, editorTabColumn) : footer;
        assertTrue(editorTabColumn > 0 && footerLeftOfTabs.contains("Read File"),
                "the project pane footer runs left of the bottom tab row:\n" + mainArea);
    }

    /**
     * The panes are islands on the frame, so the rendered main area has no box
     * drawing line between the panes. The gap cells are half blocks on the Unicode
     * icon face and plain frame cells on the ASCII face, which is the face this
     * endpoint renders with, so the check covers the line glyphs only.
     */
    @Test
    void showsNoSeparatorLineBetweenThePanes() throws InterruptedException {
        String mainArea = awaitSettledMainArea();
        assertFalse(mainArea.contains("│"), "no vertical separator line between the panes:\n" + mainArea);
        assertFalse(mainArea.contains("─"), "no horizontal separator line above the bottom windows:\n" + mainArea);
    }

    private static String awaitSettledMainArea() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String mainArea = "";
        while (System.nanoTime() < deadline) {
            mainArea = given()
                    .when().get("/tui/render")
                    .then().statusCode(200)
                    .extract().path("mainArea");
            if (mainArea.contains("Read File") && !mainArea.contains("checking")) {
                break;
            }
            Thread.sleep(50);
        }
        return mainArea;
    }

    /**
     * The main toolbar row carries the menu button and the project widget in
     * either glyph face: the Unicode glyphs or their ASCII fallback. The project
     * name depends on the directory the test JVM or the native runner starts in,
     * so the assertion checks the widget shape and not the name.
     */
    @Test
    void showsTheMainToolbarWithTheMenuButtonAndTheProjectWidget() {
        String toolbar = given()
                .when().get("/tui/render")
                .then().statusCode(200)
                .extract().path("toolbar");
        assertTrue(toolbar.matches("^ (≡|=) .*"), "the menu button leads the toolbar: " + toolbar);
        assertTrue(toolbar.matches("^ (≡|=) .+ (▾|v) .*"), "the project widget carries a dropdown mark: " + toolbar);
    }

    @Test
    void exposesToolWindowTabsAndStatusFromAnOffscreenFrame() {
        given()
                .when().get("/tui/render")
                .then()
                .statusCode(200)
                .body("tabRow", containsString("Project"),
                        "tabRow", not(containsString("4 ACP")),
                        "tabRow", endsWith(" A"),
                        "mainAreaTop", not(isEmptyOrNullString()),
                        "mainAreaTop", not(containsString("╭")),
                        "status", not(isEmptyOrNullString()),
                        "status", containsString("R:"),
                        "status", containsString("T:"),
                        "status", containsString("P:"),
                        "status", containsString("IDEA:"),
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
     * The chords the TamboUI preset itself supplies: {@code moveUp} and
     * {@code moveDown} are the arrow keys of the shipped standard set and are
     * not touched by the shell, so they are the pair in this payload that a
     * preset which is not applied at all would leave empty. The chords
     * Vintelli binds itself (quit, the palette and the tool window
     * activations) survive such a preset, so they cannot show it. The
     * inherited IT replays this on the native runner, where the preset has to
     * be readable for this call to answer at all.
     */
    @Test
    void exposesThePresetChordsOfTheShippedStandardSet() {
        given()
                .when().get("/tui/keymap")
                .then()
                .statusCode(200)
                .body("shortcuts.moveDown", equalTo("Down"),
                        "shortcuts.moveUp", equalTo("Up"));
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
                            "tabRow", containsString("Project"),
                            "mainAreaTop", not(isEmptyOrNullString()));
            given()
                    .when().get("/tui/render")
                    .then()
                    .statusCode(200)
                    .body("theme", is("darcula"),
                            "status", not(isEmptyOrNullString()));
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
