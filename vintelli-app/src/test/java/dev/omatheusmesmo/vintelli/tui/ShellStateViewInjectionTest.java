package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellStateView;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that consumers outside the TUI, such as the MCP server, can inject
 * the read-only {@link ShellStateView} port and receive the very state the
 * shell writes to.
 */
@QuarkusTest
class ShellStateViewInjectionTest {

    private final ShellStateView view;
    private final VintelliShell shell;

    @Inject
    ShellStateViewInjectionTest(ShellStateView view, VintelliShell shell) {
        this.view = view;
        this.shell = shell;
    }

    @Test
    void injectsTheReadOnlyPortBackedByTheStateTheShellWrites() {
        assertSame(shell.state(), view);
        assertNotNull(view.snapshot());
    }
}
