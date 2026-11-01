package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Resolves symbol definitions and references from a document position. */
public interface NavigationCapability extends ProviderDescriptor {

    /** @return locations containing the definition of the symbol at {@code position} */
    CapabilityResult<List<Location>> definition(SourcePosition position);

    /** @return locations containing references to the symbol at {@code position} */
    CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration);

    /**
     * @return the document outline (classes, methods, fields, ...) the server
     *         returns for the file, or a failed result when the provider cannot
     *         answer
     */
    CapabilityResult<List<DocumentSymbol>> documentSymbols(Path file);

    /**
     * Alias for {@link #documentSymbols(Path)} matching the LSP request name.
     */
    default CapabilityResult<List<DocumentSymbol>> documentSymbol(Path file) {
        return documentSymbols(file);
    }

    /**
     * Alias for {@link #documentSymbols(Path)}.
     */
    default CapabilityResult<List<DocumentSymbol>> symbols(Path file) {
        return documentSymbols(file);
    }

    /** A source document position, with zero based line and UTF-16 column. */
    record SourcePosition(Path filePath, int line, int column) {
        public SourcePosition {
            Objects.requireNonNull(filePath, "filePath");
        }
    }

    /** A resolved file position, with zero based line and UTF-16 column. */
    record Location(Path filePath, int line, int column) {
        public Location {
            Objects.requireNonNull(filePath, "filePath");
        }
    }

    /** One node of a document outline. */
    record DocumentSymbol(String name, int kind, int startLine, int startColumn, int endLine, int endColumn,
            int selectionStartLine, int selectionStartColumn, int selectionEndLine, int selectionEndColumn,
            List<DocumentSymbol> children) {
        public DocumentSymbol {
            Objects.requireNonNull(name, "name");
            if (children == null) {
                children = List.of();
            } else {
                children = List.copyOf(children);
            }
        }
    }
}
