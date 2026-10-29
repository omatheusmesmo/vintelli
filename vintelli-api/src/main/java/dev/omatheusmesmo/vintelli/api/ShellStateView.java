package dev.omatheusmesmo.vintelli.api;

/**
 * Read-only port to the TUI shell state. Consumers such as the MCP server
 * inject this view and take {@link ShellStateSnapshot} values from it; the
 * TUI shell owns the only writable holder behind the port.
 */
public interface ShellStateView {

    /**
     * The current selection as one consistent snapshot.
     */
    ShellStateSnapshot snapshot();
}
