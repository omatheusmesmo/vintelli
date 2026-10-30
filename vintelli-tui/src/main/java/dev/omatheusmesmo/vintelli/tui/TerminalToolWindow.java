package dev.omatheusmesmo.vintelli.tui;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Pilot Terminal tool window; replaced when the terminal capability lands.
 */
@Singleton
public final class TerminalToolWindow extends PilotToolWindow {

    @Inject
    public TerminalToolWindow(Provider<ShellThemes> themes) {
        super("terminal", "Terminal", '2', 1,
                "IDEA integrated terminal panel (MCP: execute_terminal_command)", themes);
    }
}
