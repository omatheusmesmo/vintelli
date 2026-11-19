package dev.omatheusmesmo.vintelli.lsp;

/**
 * One occurrence of a symbol a language server returns for {@code textDocument/documentHighlight}: the range in zero
 * based lines and UTF 16 columns, with the end exclusive, and the LSP kind number (1 text, 2 read, 3 write).
 */
public record DocumentHighlightEntry(int startLine, int startColumn, int endLine, int endColumn, int kind) {
}
