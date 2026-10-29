package dev.omatheusmesmo.vintelli.api;

import java.util.Objects;

/**
 * Immutable snapshot of the current TUI shell state: the selected tool window
 * id and the selected file read together consistently. Plain Java types only,
 * so surfaces such as the MCP server can consume it without a TamboUI
 * dependency.
 *
 * @param selectedToolWindowId stable id of the selected tool window, or an
 *                             empty string when nothing is selected yet
 * @param selectedFile         path of the selected file in the project tree,
 *                             or an empty string when nothing is selected
 */
public record ShellStateSnapshot(String selectedToolWindowId, String selectedFile) {

    public ShellStateSnapshot {
        Objects.requireNonNull(selectedToolWindowId, "selectedToolWindowId");
        Objects.requireNonNull(selectedFile, "selectedFile");
    }
}
