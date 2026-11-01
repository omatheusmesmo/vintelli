package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Re-runs {@link WebTerminalTest} against the packaged artifact, which under
 * the {@code native} profile is the Mandrel image: the guard, the basic gate,
 * the streaming render, key routing and clamped sizes must all hold on the
 * native runner.
 */
@QuarkusIntegrationTest
@WithTestResource(value = WebTerminalResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalIT extends WebTerminalTest {

    /**
     * The shell lives in the packaged process, out of reach of CDI from the
     * test JVM, so there is no shared state to reset here.
     */
    @BeforeEach
    @Override
    void resetSharedState() {
    }

    @AfterEach
    @Override
    void closeInteractiveShell() {
    }
}
