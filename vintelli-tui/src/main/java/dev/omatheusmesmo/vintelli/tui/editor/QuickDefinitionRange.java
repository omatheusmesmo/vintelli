package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentSymbol;

/**
 * Picks the lines the quick definition popup shows for a target: the innermost declaration of the file outline that
 * contains the target, capped at {@link #MAX_LINES}, or {@link #FALLBACK_LINES} lines from the target line when the
 * outline has no such declaration.
 *
 * @param start first line of the range, zero based
 * @param end last line of the range, zero based and inclusive
 */
record QuickDefinitionRange(int start, int end) {

    static final int FALLBACK_LINES = 20;
    static final int MAX_LINES = 200;

    /**
     * @param symbols the outline of the target file, empty when the server could not answer
     * @param line zero based target line
     * @param column zero based target column
     * @param lineCount number of lines of the target file
     * @return a range inside the file, never empty for a file that has a line
     */
    static QuickDefinitionRange of(List<DocumentSymbol> symbols, int line, int column, int lineCount) {
        int last = Math.max(0, lineCount - 1);
        int target = Math.clamp(line, 0, last);
        DocumentSymbol declaration = innermost(symbols, line, column);
        if (declaration == null) {
            return new QuickDefinitionRange(target, Math.min(last, target + FALLBACK_LINES - 1));
        }
        int start = Math.clamp(declaration.startLine(), 0, last);
        int end = declaration.endColumn() == 0 && declaration.endLine() > declaration.startLine()
                ? declaration.endLine() - 1 : declaration.endLine();
        return new QuickDefinitionRange(start, Math.clamp(end, start, Math.min(last, start + MAX_LINES - 1)));
    }

    private static DocumentSymbol innermost(List<DocumentSymbol> symbols, int line, int column) {
        for (DocumentSymbol symbol : symbols) {
            if (!contains(symbol, line, column)) {
                continue;
            }
            DocumentSymbol inner = innermost(symbol.children(), line, column);
            return inner == null ? symbol : inner;
        }
        return null;
    }

    private static boolean contains(DocumentSymbol symbol, int line, int column) {
        boolean afterStart = line > symbol.startLine() || line == symbol.startLine() && column >= symbol.startColumn();
        boolean beforeEnd = line < symbol.endLine() || line == symbol.endLine() && column <= symbol.endColumn();
        return afterStart && beforeEnd;
    }
}
