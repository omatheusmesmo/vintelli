package dev.omatheusmesmo.vintelli.lsp;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One symbol a language server returns for {@code workspace/symbol}: its name, LSP kind number and container, plus
 * the file and the zero based start position of its location. The file is empty when the location is not a file
 * the editor can open, such as a library type that jdtls serves from its own class storage.
 */
public record WorkspaceSymbolEntry(String name, int kind, String container, Optional<Path> file, int line,
        int column) {

    public WorkspaceSymbolEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(file, "file");
    }
}
