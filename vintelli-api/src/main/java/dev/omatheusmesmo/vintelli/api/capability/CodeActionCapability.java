package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Offers code actions (quick fixes, source actions) for a range of a file: the
 * LSP {@code textDocument/codeAction} request served by the generic LSP client,
 * or by any other provider that can name fixes for a range. Results carry
 * Vintelli-owned records only; applying an edit is the job of the editor.
 */
public interface CodeActionCapability extends ProviderDescriptor {

    /**
     * @param file file the actions apply to
     * @param range zero based range the actions are requested for
     * @param diagnostics problems overlapping the range, handed to the server as context
     * @return the actions the provider offers, an empty list when there are none, or a
     *         failed result when the language services cannot answer or answer malformed
     */
    CapabilityResult<List<CodeAction>> codeActions(Path file, SourceRange range,
            List<DiagnosticsCapability.Diagnostic> diagnostics);

    /**
     * Zero based range with the UTF-16 column semantics of the Language Server Protocol.
     *
     * @param startLine zero based start line
     * @param startColumn zero based start column in UTF-16 code units
     * @param endLine zero based end line
     * @param endColumn zero based end column in UTF-16 code units
     */
    record SourceRange(int startLine, int startColumn, int endLine, int endColumn) {
    }

    /**
     * One action the provider offers. An action carries an edit, a command, or both; a
     * command-only action cannot be applied by the editor yet.
     *
     * @param title text shown to the user
     * @param kind action kind such as {@code quickfix}, empty when the provider sent none
     * @param preferred whether the provider marks the action as the preferred fix
     * @param edit workspace edit the action applies, or null
     * @param command command the action runs, or null
     */
    record CodeAction(String title, String kind, boolean preferred, WorkspaceEdit edit, CommandRef command) {

        public CodeAction {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("A code action always has a title");
            }
            kind = kind == null ? "" : kind;
        }
    }

    /**
     * Command the server attaches to an action.
     *
     * @param identifier command identifier the server understands
     * @param title text of the command
     */
    record CommandRef(String identifier, String title) {

        public CommandRef {
            Objects.requireNonNull(identifier, "identifier");
            title = title == null ? "" : title;
        }
    }

    /**
     * Edits one action applies. Text edits are grouped per document; anything the
     * editor cannot apply yet (resource operations, edits of documents that are not
     * files) is listed as unsupported so the caller can reject the whole edit.
     *
     * @param documents text edits per document, in the order the server sent them
     * @param unsupported changes the editor cannot apply
     */
    record WorkspaceEdit(List<DocumentEdit> documents, List<UnsupportedChange> unsupported) {

        public WorkspaceEdit {
            documents = documents == null ? List.of() : List.copyOf(documents);
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }

        /**
         * Text edits of one document.
         *
         * @param file the document the edits apply to
         * @param edits replacements, with zero based ranges in UTF-16 code units
         */
        public record DocumentEdit(Path file, List<TextEdit> edits) {

            public DocumentEdit {
                Objects.requireNonNull(file, "file");
                edits = edits == null ? List.of() : List.copyOf(edits);
            }
        }

        /**
         * Replacement of one range of a document.
         *
         * @param startLine zero based start line
         * @param startColumn zero based start column in UTF-16 code units
         * @param endLine zero based end line
         * @param endColumn zero based end column in UTF-16 code units
         * @param newText replacement text
         */
        public record TextEdit(int startLine, int startColumn, int endLine, int endColumn, String newText) {

            public TextEdit {
                newText = newText == null ? "" : newText;
            }
        }

        /**
         * A change the editor cannot apply yet.
         *
         * @param kind for example {@code create}, {@code rename}, {@code delete} or {@code non-file}
         * @param target the path or URI the change refers to
         */
        public record UnsupportedChange(String kind, String target) {

            public UnsupportedChange {
                Objects.requireNonNull(kind, "kind");
                target = target == null ? "" : target;
            }
        }
    }
}
