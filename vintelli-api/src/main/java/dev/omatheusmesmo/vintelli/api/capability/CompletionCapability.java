package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Offers code completion for a position of a file: the LSP {@code textDocument/completion} request served
 * by the generic LSP client, or by any other provider that can name the completions of a position. Results
 * carry Vintelli-owned records only; inserting a suggestion is the job of the editor.
 */
public interface CompletionCapability extends ProviderDescriptor {

    /**
     * Asks for the suggestions at a position. The provider takes {@code content} as the current text of the
     * file, so suggestions match unsaved edits.
     *
     * @param file file the position belongs to
     * @param content the current text of the file, including unsaved edits
     * @param line zero based line of the caret
     * @param column zero based column of the caret in UTF-16 code units
     * @return the suggestions in the order the provider ranks them, an empty list when there are none, or a
     *         failed result when the language services cannot answer or answer malformed
     */
    CapabilityResult<List<Suggestion>> complete(Path file, String content, int line, int column);

    /**
     * What a suggestion stands for, as the language server classifies it.
     */
    enum Kind {
        TEXT, METHOD, FUNCTION, CONSTRUCTOR, FIELD, VARIABLE, CLASS, INTERFACE, MODULE, PROPERTY, UNIT, VALUE,
        ENUM, KEYWORD, SNIPPET, COLOR, FILE, REFERENCE, FOLDER, ENUM_MEMBER, CONSTANT, STRUCT, EVENT, OPERATOR,
        TYPE_PARAMETER, UNKNOWN
    }

    /**
     * One suggestion. The editor inserts the {@code textEdit} when there is one, otherwise the
     * {@code insertText}, otherwise the label, and matches the typed text against the {@code filterText}, or
     * the label when no filter text is sent.
     *
     * @param label text shown in the popup
     * @param kind classification of the suggestion, {@link Kind#UNKNOWN} when the server sent none
     * @param detail signature or type, empty when the server sent none
     * @param textEdit replacement the server asks for, or null
     * @param insertText text to insert when there is no text edit, or null
     * @param snippet whether the inserted text uses snippet syntax such as {@code $1} or {@code ${1:name}}
     * @param filterText text the typed prefix is matched against, or null
     * @param sortText ordering key the server sent, or null
     */
    record Suggestion(String label, Kind kind, String detail, Replacement textEdit, String insertText,
            boolean snippet, String filterText, String sortText) {

        public Suggestion {
            Objects.requireNonNull(label, "label");
            kind = kind == null ? Kind.UNKNOWN : kind;
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * Replacement of one range of the file. Zero based range with the UTF-16 column semantics of the Language
     * Server Protocol.
     *
     * @param startLine zero based start line
     * @param startColumn zero based start column in UTF-16 code units
     * @param endLine zero based end line
     * @param endColumn zero based end column in UTF-16 code units
     * @param newText text that replaces the range
     */
    record Replacement(int startLine, int startColumn, int endLine, int endColumn, String newText) {

        public Replacement {
            newText = newText == null ? "" : newText;
        }
    }
}
