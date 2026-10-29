package dev.omatheusmesmo.vintelli.lsp;

import java.util.Objects;

/**
 * One landing point a definition or reference request resolved to: the target
 * file plus the range inside it in zero based lines and UTF 16 columns.
 */
public record CodeReference(String targetFile, int startLine, int startColumn, int endLine, int endColumn) {

    public CodeReference {
        Objects.requireNonNull(targetFile, "targetFile");
    }
}
