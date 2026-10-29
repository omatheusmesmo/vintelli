package dev.omatheusmesmo.vintelli.api;

/**
 * Port to push a highlight into the TUI shell. Consumers such as the MCP
 * server inject this port; the TUI shell is the implementer and keeps the
 * only writable state behind it. Plain Java types only, so callers never
 * depend on TamboUI.
 */
public interface ShellHighlightPort {

    /**
     * Marks a file and line as highlighted in the shell editor panel.
     */
    void highlight(ShellHighlightTarget target);
}
