package dev.omatheusmesmo.vintelli.tui;

import jakarta.inject.Singleton;

/**
 * Pilot Terminal tool window; replaced when the terminal capability lands.
 */
@Singleton
public final class TerminalToolWindow extends PilotToolWindow {

    public TerminalToolWindow() {
        super("terminal", "Terminal", '2', 1,
                "IDEA integrated terminal panel (MCP: execute_terminal_command)");
    }
}
