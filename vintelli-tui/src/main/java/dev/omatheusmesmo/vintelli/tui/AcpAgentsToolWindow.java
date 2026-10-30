package dev.omatheusmesmo.vintelli.tui;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Pilot ACP agents tool window hosting future agent sessions (#7).
 */
@Singleton
public final class AcpAgentsToolWindow extends PilotToolWindow {

    @Inject
    public AcpAgentsToolWindow(ShellThemes themes) {
        super("acp.agents", "ACP Agents", '4', 3,
                "ACP agent sessions: opencode acp / claude-code-acp / pi-acp", themes);
    }
}
