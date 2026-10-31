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
}
