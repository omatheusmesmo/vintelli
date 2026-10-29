package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Resolves call hierarchies for a code position, served by the generic LSP
 * client through the {@code callHierarchy/*} requests at default, by the
 * optional IDEA provider otherwise. The request carries a position, not a bare
 * symbol: resolving the definition from a document position is unambiguous.
 */
public interface CallHierarchyCapability extends ProviderDescriptor {

    /**
     * @param position position of the symbol to resolve
     * @return call sites that call the symbol under the position, or a failed
     *         result when the position carries no resolvable symbol or the
     *         language services cannot answer yet
     */
    CapabilityResult<List<CallSite>> callers(SourcePosition position);

    /**
     * @param position position of the symbol to resolve
     * @return call sites the symbol under the position calls, or a failed
     *         result when the position carries no resolvable symbol or the
     *         language services cannot answer yet
     */
    CapabilityResult<List<CallSite>> callees(SourcePosition position);

    record SourcePosition(Path filePath, int line, int column) {

        public SourcePosition {
            Objects.requireNonNull(filePath, "filePath");
        }
    }

    record CallSite(String symbol, SourcePosition position) {

        public CallSite {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("A call site always names its symbol");
            }
            Objects.requireNonNull(position, "position");
        }
    }
}
