package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.vcs.TemporaryGitRepositoryResource;
import dev.omatheusmesmo.vintelli.web.InspectEnabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project pane of the offscreen frame over a real Git worktree whose tracked file is modified and whose
 * untracked file is new. The VCS markers must reach the slot of their rows and no tree connector may be drawn.
 * {@link VintelliShellVcsRenderIT} replays the same assertions against the packaged artifact.
 */
@QuarkusTest
@WithTestResource(value = InspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
@WithTestResource(value = TemporaryGitRepositoryResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliShellVcsRenderTest {

    private static final Pattern MODIFIED_ROW = Pattern.compile("M .*tracked\\.txt");
    private static final Pattern UNTRACKED_ROW = Pattern.compile("\\? .*untracked\\.txt");

    @Test
    void drawsTheVcsMarkersInTheSlotWithoutTreeConnectors() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String mainArea = "";
        while (System.nanoTime() < deadline) {
            mainArea = given()
                    .when().get("/tui/render")
                    .then().statusCode(200)
                    .extract().path("mainArea");
            if (MODIFIED_ROW.matcher(mainArea).find()) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(MODIFIED_ROW.matcher(mainArea).find(),
                "the modified file must keep its tree row and marker:\n" + mainArea);
        assertTrue(UNTRACKED_ROW.matcher(mainArea).find(),
                "the untracked file must keep its tree row and marker:\n" + mainArea);
        assertFalse(mainArea.contains("M tracked.txt"), "rows must not fall back to flat paths:\n" + mainArea);
        assertFalse(mainArea.contains("├──") || mainArea.contains("└──") || mainArea.contains("|-- ")
                || mainArea.contains("\\-- "), "the tree draws no connectors:\n" + mainArea);
    }
}
