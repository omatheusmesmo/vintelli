package dev.omatheusmesmo.vintelli.lsp;

import java.util.List;
import java.util.Objects;

import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Kind;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Replacement;
import org.eclipse.lsp4j.CompletionItem;

/**
 * One element the completion request returned, with the parts the editor needs to insert it: the label, the
 * kind and detail the server attached (empty when absent), the text edit or insert text, whether the insert
 * text is a snippet, the filter and sort keys and the additional edits such as an import. Values are empty or
 * null when the server sent none.
 *
 * @param resolveItem the item as the server sent it, kept to send {@code completionItem/resolve} for it; null
 *        unless the item has no additional edits and the server advertises resolve
 */
public record CompletionEntry(String label, Kind kind, String detail, Replacement textEdit, String insertText,
        boolean snippet, String filterText, String sortText, List<Replacement> additionalEdits,
        CompletionItem resolveItem) {

    public CompletionEntry {
        Objects.requireNonNull(label, "label");
        kind = kind == null ? Kind.UNKNOWN : kind;
        detail = detail == null ? "" : detail;
        additionalEdits = additionalEdits == null ? List.of() : List.copyOf(additionalEdits);
    }
}
