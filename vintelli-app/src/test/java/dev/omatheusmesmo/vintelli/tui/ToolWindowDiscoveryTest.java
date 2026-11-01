package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that tool windows are discovered as CDI beans in the assembled
 * app: the pilot windows from `vintelli-tui` and a bean that exists only in
 * the test classpath both reach the registry, sorted by the SPI contract,
 * with no change to {@code VintelliShell}.
 */
@QuarkusTest
class ToolWindowDiscoveryTest {

    private final ToolWindowRegistry registry;

    @Inject
    ToolWindowDiscoveryTest(ToolWindowRegistry registry) {
        this.registry = registry;
    }

    @Test
    void discoversToolWindowBeansInTabOrderIncludingTestOnes() {
        assertEquals(List.of(
                        "run",
                        "sample.extra",
                        "terminal",
                        "problems",
                        "acp.agents",
                        "structure",
                        "editor"),
                registry.toolWindows().stream()
                        .map(ToolWindow::id)
                        .toList());
    }
}
