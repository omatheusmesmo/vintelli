package dev.omatheusmesmo.vintelli.tui;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Pilot Run tool window; replaced by the real run view when the run
 * capability lands.
 */
@Singleton
public final class RunToolWindow extends PilotToolWindow {

    @Inject
    public RunToolWindow(Provider<ShellThemes> themes) {
        super("run", "Run", '1', 0,
                "IDEA run configurations will stream here (MCP: execute_run_configuration)", themes);
    }
}
