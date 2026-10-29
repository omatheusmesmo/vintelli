package dev.omatheusmesmo.vintelli.vcs;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Drives the version control provider through its HTTP anchor against the
 * packaged artifact (the native runner under {@code -Dnative}), so the JGit
 * code path executes inside the image instead of only compiling in it. The
 * repository under test is a real temporary Git repository that JGit itself
 * prepared before the application booted: JGit reflection, file system
 * substitutions and resources are therefore exercised in the image on every
 * call, and any missing registration fails the test.
 */
@QuarkusIntegrationTest
@WithTestResource(TemporaryGitRepositoryResource.class)
class VintelliVcsRepositoryIT {

    @Test
    void reportsTheBranchAndTheWorktreeStates() {
        given().get("/vcs/status").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("branch", equalTo("main"))
                .body("entries.find { it.path == 'tracked.txt' }.state", equalTo("MODIFIED"))
                .body("entries.find { it.path == 'untracked.txt' }.state", equalTo("UNTRACKED"));
    }

    @Test
    void diffsTheModifiedFileAgainstHeadWithGitPrefixes() {
        given().queryParam("file", "tracked.txt").get("/vcs/diff").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", containsString("diff --git a/tracked.txt b/tracked.txt"))
                .body("text", containsString("+++ b/tracked.txt"))
                .body("text", containsString("second line"));
    }

    @Test
    void refusesADiffPathOutsideTheRepository() {
        String outside = TemporaryGitRepositoryResource.repositoryRoot().getParent().toString();

        given().queryParam("file", outside).get("/vcs/diff").then()
                .statusCode(409)
                .body("state", equalTo("FAILED"))
                .body("detail", containsString("outside the project root"));
    }

    @Test
    void requiresAFileForTheDiff() {
        given().get("/vcs/diff").then()
                .statusCode(400)
                .body("state", equalTo("REJECTED"));
    }

    @Test
    void rejectsANullByteInTheDiffPath() {
        given().queryParam("file", "tracked\0.txt").get("/vcs/diff").then()
                .statusCode(400)
                .body("state", equalTo("REJECTED"));
    }

    @Test
    void rejectsAnAbsurdlyLongDiffPath() {
        given().queryParam("file", "a".repeat(2048)).get("/vcs/diff").then()
                .statusCode(400)
                .body("state", equalTo("REJECTED"));
    }

    @Test
    void answersAnEmptyDiffForTheUntrackedPathsItCannotCompare() {
        given().queryParam("file", "untracked.txt").get("/vcs/diff").then()
                .statusCode(200)
                .body("state", equalTo("OK"))
                .body("text", equalTo(""));
    }
}
