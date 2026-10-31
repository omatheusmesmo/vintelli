package dev.omatheusmesmo.vintelli.lsp;

import java.util.List;
import java.util.Objects;

import org.eclipse.lsp4j.SymbolKind;

/**
 * One node of the document outline a language server returns for
 * {@code textDocument/documentSymbol}: its name, kind, full and selection
 * ranges in zero based lines and UTF-16 columns, plus its nested children.
 */
public record DocumentSymbol(String name, String detail, SymbolKind kind,
        int startLine, int startColumn, int endLine, int endColumn,
        int selectionStartLine, int selectionStartColumn, int selectionEndLine, int selectionEndColumn,
        List<DocumentSymbol> children) {

    public DocumentSymbol {
        Objects.requireNonNull(name, "name");
        detail = detail == null ? "" : detail;
        Objects.requireNonNull(kind, "kind");
        if (children == null) {
            children = List.of();
        } else {
            children = List.copyOf(children);
        }
    }
}
