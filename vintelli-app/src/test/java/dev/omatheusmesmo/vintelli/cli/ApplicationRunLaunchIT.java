package dev.omatheusmesmo.vintelli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import dev.omatheusmesmo.vintelli.capabilities.ApplicationRunProjectResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@WithTestResource(value = ApplicationRunProjectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ApplicationRunLaunchIT {

    @Test
    void detectedApplicationGoalsReachTheProjectWrapper() throws Exception {
        for (String goal : new String[] { "quarkus:dev", "spring-boot:run" }) {
            var run = VintelliCliCommandsIT.run(Map.of("vintelli.local.project-root", ApplicationRunProjectResource.projectRoot().toString()),
                    null, "run", "maven:" + goal);
            assertEquals(0, run.exitCode(), run.describe());
            assertTrue(run.stdout().contains("start:" + goal), run.describe());
        }
    }

    @Test
    void commandWaitsForTheFinalOutputAndReportsTheBuildFailure() throws Exception {
        var run = VintelliCliCommandsIT.run(Map.of("vintelli.local.project-root", ApplicationRunProjectResource.projectRoot().toString()),
                null, "run", "maven:test");
        assertEquals(1, run.exitCode(), run.describe());
        assertTrue(run.stdout().contains("start:test"), run.describe());
        assertTrue(run.stdout().contains("Run finished with exit code 7"), run.describe());
    }
}
