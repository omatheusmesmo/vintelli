package dev.omatheusmesmo.vintelli.api;

/**
 * Port to push a unified diff into the TUI shell. Consumers such as the MCP
 * server inject this port; the TUI shell is the implementer and keeps the
 * only writable state behind it. Plain Java types only, so callers never
 * depend on TamboUI.
 */
public interface ShellDiffViewPort {

    /**
     * Renders a unified diff in the shell editor panel.
     */
    void show(ShellUnifiedDiff diff);
}
