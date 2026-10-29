package dev.omatheusmesmo.vintelli.tui;

import jakarta.inject.Singleton;

/**
 * Pilot Problems tool window; replaced when diagnostics become a capability.
 */
@Singleton
public final class ProblemsToolWindow extends PilotToolWindow {

    public ProblemsToolWindow() {
        super("problems", "Problems", '3', 2,
                "Problems view: offline inspections via idea.sh inspect + MCP analysis tools");
    }
}
