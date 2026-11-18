package dev.omatheusmesmo.vintelli.lsp;

import java.util.Objects;

import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Kind;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Replacement;

/**
 * One element the completion request returned, with the parts the editor needs to insert it: the label, the
 * kind and detail the server attached (empty when absent), the text edit or insert text, whether the insert
 * text is a snippet, and the filter and sort keys. Values are empty or null when the server sent none.
 */
public record CompletionEntry(String label, Kind kind, String detail, Replacement textEdit, String insertText,
        boolean snippet, String filterText, String sortText) {

    public CompletionEntry {
        Objects.requireNonNull(label, "label");
        kind = kind == null ? Kind.UNKNOWN : kind;
        detail = detail == null ? "" : detail;
    }
}
