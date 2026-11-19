package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;

/**
 * Renames the symbol at a position and every reference to it across the project: the LSP
 * {@code textDocument/prepareRename} and {@code textDocument/rename} requests served by the generic LSP client, or
 * by any other provider that can rename a symbol. Results carry Vintelli-owned records only; applying the returned
 * edit is the job of the editor.
 */
public interface RenameCapability extends ProviderDescriptor {

    /**
     * Asks whether the symbol at a position can be renamed, and for the name the provider suggests for it.
     *
     * @param file file the position belongs to
     * @param content the current text of the file, including unsaved edits
     * @param line zero based line of the caret
     * @param column zero based column of the caret in UTF-16 code units
     * @return the suggested name, empty when the provider suggests none, or a failed result with reason
     *         {@code not-resolvable} when the position names nothing that can be renamed
     */
    CapabilityResult<String> prepareRename(Path file, String content, int line, int column);

    /**
     * Asks for the edits that rename the symbol at a position to {@code newName}. The edits may touch several
     * files; a file rename among them is reported as an unsupported change of the edit.
     *
     * @param file file the position belongs to
     * @param content the current text of the file, including unsaved edits
     * @param line zero based line of the caret
     * @param column zero based column of the caret in UTF-16 code units
     * @param newName the new name of the symbol
     * @return the edits of the rename, or a failed result when the provider cannot answer
     */
    CapabilityResult<WorkspaceEdit> rename(Path file, String content, int line, int column, String newName);
}
