package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Reports code problems for a file: LSP publish diagnostics or the Problems
 * view of the optional IDEA provider.
 */
public interface DiagnosticsCapability extends ProviderDescriptor {

    /**
     * @param file file whose problems are requested
     * @return all problems the provider currently knows for the file, or a
     *         failed result when the language services cannot answer yet, for
     *         example while the server is still indexing
     */
    CapabilityResult<List<Diagnostic>> diagnostics(Path file);

    /**
     * @param filePath file the problem points at
     * @param startLine zero based start line
     * @param startColumn zero based start column in UTF-16 code units
     * @param endLine zero based end line
     * @param endColumn zero based end column in UTF-16 code units
     * @param severity problem severity
     * @param source producer of the problem, for example {@code javac}
     * @param message problem text shown to the user
     * @param deprecated the range names a symbol the language server marks as
     *        deprecated, which editors show struck through; a display hint that
     *        is independent of the severity
     */
    record Diagnostic(Path filePath, int startLine, int startColumn, int endLine, int endColumn, Severity severity,
            String source, String message, boolean deprecated) {

        public Diagnostic {
            Objects.requireNonNull(filePath, "filePath");
            Objects.requireNonNull(severity, "severity");
            source = source == null ? "" : source;
            Objects.requireNonNull(message, "message");
        }

        public Diagnostic(Path filePath, int startLine, int startColumn, int endLine, int endColumn, Severity severity,
                String source, String message) {
            this(filePath, startLine, startColumn, endLine, endColumn, severity, source, message, false);
        }
    }

    /**
     * Severity of one {@link Diagnostic}.
     */
    enum Severity {
        ERROR,
        WARNING,
        INFORMATION,
        HINT
    }
}
