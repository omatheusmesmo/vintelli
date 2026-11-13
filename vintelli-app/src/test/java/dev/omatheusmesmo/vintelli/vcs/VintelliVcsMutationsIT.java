package dev.omatheusmesmo.vintelli.vcs;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;

/** Exercises confirmed JGit writes through the packaged application's headless surface. */
@QuarkusIntegrationTest
@WithTestResource(TemporaryGitRepositoryResource.class)
class VintelliVcsMutationsIT {

    @Test
    void confirmsAndConfinesWritesAndChecksOutExistingLocalBranches() throws IOException, GitAPIException {
        given().queryParam("file", "tracked.txt").put("/vcs/stage").then()
                .statusCode(428)
                .body("state", equalTo("CONFIRMATION_REQUIRED"));
        given().queryParam("file", "tracked.txt").put("/vcs/unstage").then()
                .statusCode(428)
                .body("state", equalTo("CONFIRMATION_REQUIRED"));
        given().body("{\"message\":\"must not commit\"}").contentType("application/json")
                .post("/vcs/commit").then()
                .statusCode(428)
                .body("state", equalTo("CONFIRMATION_REQUIRED"));
        given().body("{\"message\":\"must not amend\",\"amend\":true}").contentType("application/json")
                .post("/vcs/commit").then()
                .statusCode(428)
                .body("state", equalTo("CONFIRMATION_REQUIRED"));
        given().body("{\"name\":\"feature\"}").contentType("application/json")
                .put("/vcs/branch").then()
                .statusCode(428)
                .body("state", equalTo("CONFIRMATION_REQUIRED"));

        given().queryParam("file", Path.of("..").resolve("outside.txt").toString())
                .queryParam("confirm", true).put("/vcs/stage").then()
                .statusCode(409)
                .body("state", equalTo("FAILED"))
                .body("detail", containsString("outside the project root"));

        given().queryParam("file", "tracked.txt").queryParam("confirm", true)
                .put("/vcs/stage").then()
                .statusCode(200)
                .body("success", equalTo(true));
        given().get("/vcs/status").then()
                .statusCode(200)
                .body("entries.find { it.path == 'tracked.txt' }.state", equalTo("MODIFIED"));

        given().queryParam("file", "tracked.txt").queryParam("confirm", true)
                .put("/vcs/unstage").then()
                .statusCode(200)
                .body("success", equalTo(true));

        given().queryParam("confirm", true).body("{\"name\":\"missing\"}")
                .contentType("application/json").put("/vcs/branch").then()
                .statusCode(409)
                .body("state", equalTo("FAILED"));
        given().queryParam("confirm", true).body("{\"name\":\"feature\"}")
                .contentType("application/json").put("/vcs/branch").then()
                .statusCode(200)
                .body("success", equalTo(true));
        given().get("/vcs/status").then()
                .statusCode(200)
                .body("branch", equalTo("feature"));

        given().queryParam("file", "tracked.txt").queryParam("confirm", true)
                .put("/vcs/stage").then().statusCode(200);
        given().queryParam("confirm", true).body("{\"message\":\"Update tracked file\"}")
                .contentType("application/json").post("/vcs/commit").then()
                .statusCode(200)
                .body("success", equalTo(true));

        given().queryParam("confirm", true).body("{\"name\":\"main\"}")
                .contentType("application/json").put("/vcs/branch").then()
                .statusCode(200)
                .body("success", equalTo(true));
        given().get("/vcs/status").then()
                .statusCode(200)
                .body("branch", equalTo("main"))
                .body("entries.size()", equalTo(1))
                .body("entries[0].path", equalTo("untracked.txt"));

        Files.writeString(TemporaryGitRepositoryResource.repositoryRoot().resolve("tracked.txt"), "amended line\n");
        given().queryParam("file", "tracked.txt").queryParam("confirm", true)
                .put("/vcs/stage").then().statusCode(200).body("success", equalTo(true));
        given().queryParam("confirm", true).body("{\"message\":\"Amended on main\",\"amend\":true}")
                .contentType("application/json").post("/vcs/commit").then()
                .statusCode(200)
                .body("success", equalTo(true));
        try (Git git = Git.open(TemporaryGitRepositoryResource.repositoryRoot().toFile())) {
            var commits = git.log().call().iterator();
            assertEquals("Amended on main", commits.next().getFullMessage());
            assertFalse(commits.hasNext(), "amend replaces the existing main commit");
        }
    }
}
