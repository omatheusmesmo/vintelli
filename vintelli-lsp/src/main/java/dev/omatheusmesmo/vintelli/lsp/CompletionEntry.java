package dev.omatheusmesmo.vintelli.lsp;

import java.util.Objects;

/**
 * One element the completion request returned: its edit label plus the detail
 * the server attached, empty when absent.
 */
public record CompletionEntry(String label, String detail) {

    public CompletionEntry {
        Objects.requireNonNull(label, "label");
        detail = detail == null ? "" : detail;
    }
}
