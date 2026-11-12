package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;

import java.nio.file.Path;
import java.util.List;

/**
 * What one quick fix request asks the language server: the active file, the selected or caret
 * range in it, and the diagnostics that overlap that range.
 *
 * @param file the open document the actions apply to
 * @param range zero based range in document rows and UTF-16 columns
 * @param diagnostics problems overlapping the range, handed to the server as context
 */
public record QuickFixRequest(Path file, SourceRange range, List<Diagnostic> diagnostics) {

    public QuickFixRequest {
        diagnostics = List.copyOf(diagnostics);
    }
}
