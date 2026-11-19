package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Offers code actions (quick fixes, source actions) for a range of a file: the
 * LSP {@code textDocument/codeAction} request served by the generic LSP client,
 * or by any other provider that can name fixes for a range. Formatting and the
 * organize imports action are served by the same language services, because the
 * editor applies their edits the same way. Results carry Vintelli-owned records
 * only; applying an edit is the job of the editor.
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
     * Offers the actions of a range asking for the given kinds only, when the provider can filter by kind. The
     * answer may still hold actions of other kinds, so the caller picks the kinds it wants.
     *
     * @param file file the actions apply to
     * @param range zero based range the actions are requested for
     * @param diagnostics problems overlapping the range, handed to the server as context
     * @param kinds action kinds the caller wants, such as {@code source} or {@code refactor.extract.variable}
     * @return the same outcome as {@link #codeActions(Path, SourceRange, List)}
     */
    default CapabilityResult<List<CodeAction>> codeActions(Path file, SourceRange range,
            List<DiagnosticsCapability.Diagnostic> diagnostics, List<String> kinds) {
        return codeActions(file, range, diagnostics);
    }

    /**
     * @return whether {@link #executeCommand} runs commands; the editor runs command actions only
     *         through providers that do
     */
    default boolean supportsCommands() {
        return false;
    }

    /**
     * Runs the command of an action. While the command runs, the server may ask for workspace edits;
     * each one is handed to {@code edits}, whose decision is the answer the server gets.
     *
     * @param command the command the action carries
     * @param edits receives every workspace edit the server asks to apply during the command
     * @return the number of workspace edit requests the server sent while the command ran, or a failed
     *         result with reason {@code timeout} when the server did not answer in time, {@code dropped}
     *         when the connection to the server was lost, or {@code not-advertised} when the server does
     *         not offer the command
     */
    default CapabilityResult<Integer> executeCommand(CommandRef command, WorkspaceEditSink edits) {
        return CapabilityResult.failed(new CapabilityFailure("not-supported", "the provider cannot run commands"));
    }

    /**
     * Formats the whole document the way the server formats it. The server formats the text the editor holds,
     * unsaved edits included, so the returned edits apply to the document on screen.
     *
     * @param file the document to format
     * @param content the text of the document the editor holds
     * @param options tab size and spaces-or-tabs the server formats with
     * @return the text edits that change the document, empty when it is already formatted, or a failed result
     *         when the language services cannot answer
     */
    default CapabilityResult<List<WorkspaceEdit.TextEdit>> formatDocument(Path file, String content,
            FormattingOptions options) {
        return CapabilityResult.failed(new CapabilityFailure("not-supported", "the provider cannot format documents"));
    }

    /**
     * Formats the lines of one range the way the server formats it, with the same text rules as
     * {@link #formatDocument}.
     *
     * @param file the document to format
     * @param content the text of the document the editor holds
     * @param range zero based range to format
     * @param options tab size and spaces-or-tabs the server formats with
     * @return the text edits inside the range, empty when the range is already formatted, or a failed result
     *         when the language services cannot answer
     */
    default CapabilityResult<List<WorkspaceEdit.TextEdit>> formatRange(Path file, String content, SourceRange range,
            FormattingOptions options) {
        return CapabilityResult.failed(new CapabilityFailure("not-supported", "the provider cannot format documents"));
    }

    /**
     * Asks for the source action that organizes the imports of the document, through the same request as the
     * other code actions, with the server's {@code source.organizeImports} kind.
     *
     * @param file the document whose imports are organized
     * @param content the text of the document the editor holds
     * @return the organize imports actions the server offers, empty when there is nothing to organize, or a
     *         failed result when the language services cannot answer
     */
    default CapabilityResult<List<CodeAction>> organizeImports(Path file, String content) {
        return CapabilityResult.failed(new CapabilityFailure("not-supported", "the provider cannot organize imports"));
    }

    /**
     * Options of a formatting request, the indent the server writes.
     *
     * @param tabSize columns of one indent level, at least one
     * @param insertSpaces whether indents are spaces rather than tab characters
     */
    record FormattingOptions(int tabSize, boolean insertSpaces) {

        public FormattingOptions {
            if (tabSize < 1) {
                throw new IllegalArgumentException("A tab size is at least one column");
            }
        }
    }

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
     * One action the provider offers. An action carries an edit, a command, or both; the editor
     * applies the edit and runs the command through {@link #executeCommand}.
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
     * @param arguments arguments the server sent with the command, handed back unchanged
     */
    record CommandRef(String identifier, String title, List<Object> arguments) {

        public CommandRef {
            Objects.requireNonNull(identifier, "identifier");
            title = title == null ? "" : title;
            arguments = arguments == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(arguments));
        }

        public CommandRef(String identifier, String title) {
            this(identifier, title, List.of());
        }
    }

    /**
     * Receives a workspace edit the server asks to apply while a command runs. The decision is the
     * answer sent back to the server: an accepted edit is applied by the caller, a refused one carries
     * the reason the server gets.
     */
    @FunctionalInterface
    interface WorkspaceEditSink {

        Decision accept(WorkspaceEdit edit);

        /**
         * @param accepted whether the edit is taken
         * @param reason why the edit was refused, empty when it was accepted
         */
        record Decision(boolean accepted, String reason) {

            public Decision {
                reason = reason == null ? "" : reason;
            }

            public static Decision accept() {
                return new Decision(true, "");
            }

            public static Decision refuse(String reason) {
                return new Decision(false, reason);
            }
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
