package dev.omatheusmesmo.vintelli.lsp;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.SnippetTextEdit;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Translates the LSP4J code action types into the Vintelli-owned records of the
 * {@code vintelli-api} port, and the port's diagnostics and ranges into the LSP
 * request. A payload that breaks the protocol (a missing title, an edit without a
 * range) raises {@link MalformedPayload} instead of producing a half-filled action.
 */
final class LspCodeActionMapper {

    private static final String APPLY_WORKSPACE_EDIT = "java.apply.workspaceEdit";
    private static final Gson GSON = new MessageJsonHandler(Map.of()).getGson();

    /**
     * A code action or edit the server sent that breaks the protocol.
     */
    static final class MalformedPayload extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MalformedPayload(String detail) {
            super(detail);
        }
    }

    private LspCodeActionMapper() {
    }

    /**
     * Maps the answer of {@code textDocument/codeAction}. Disabled actions are
     * left out: the protocol says they must not be applied.
     */
    static List<CodeActionCapability.CodeAction> codeActionsOf(List<Either<Command, CodeAction>> items) {
        if (items == null) {
            return List.of();
        }
        List<CodeActionCapability.CodeAction> mapped = new ArrayList<>(items.size());
        for (Either<Command, CodeAction> item : items) {
            if (item == null) {
                throw new MalformedPayload("an entry of the code action list is empty");
            }
            if (item.isLeft()) {
                mapped.add(commandOf(item.getLeft()));
            } else {
                CodeAction action = item.getRight();
                if (action == null) {
                    throw new MalformedPayload("an entry of the code action list is empty");
                }
                if (action.getDisabled() == null) {
                    mapped.add(actionOf(action));
                }
            }
        }
        return List.copyOf(mapped);
    }

    static List<Diagnostic> lspDiagnosticsOf(List<DiagnosticsCapability.Diagnostic> problems) {
        return problems.stream().map(LspCodeActionMapper::lspDiagnosticOf).toList();
    }

    static Range rangeOf(CodeActionCapability.SourceRange range) {
        return new Range(new Position(range.startLine(), range.startColumn()),
                new Position(range.endLine(), range.endColumn()));
    }

    private static Diagnostic lspDiagnosticOf(DiagnosticsCapability.Diagnostic problem) {
        return new Diagnostic(new Range(new Position(problem.startLine(), problem.startColumn()),
                new Position(problem.endLine(), problem.endColumn())), problem.message(),
                severityOf(problem.severity()), problem.source());
    }

    private static DiagnosticSeverity severityOf(DiagnosticsCapability.Severity severity) {
        return switch (severity) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
            case INFORMATION -> DiagnosticSeverity.Information;
            case HINT -> DiagnosticSeverity.Hint;
        };
    }

    private static CodeActionCapability.CodeAction commandOf(Command command) {
        if (command == null) {
            throw new MalformedPayload("an entry of the code action list is empty");
        }
        String title = requiredTitle(command.getTitle(), "a command");
        Optional<WorkspaceEdit> clientEdit = clientEditOf(command);
        if (clientEdit.isPresent()) {
            return new CodeActionCapability.CodeAction(title, "", false, editOf(clientEdit.get()), null);
        }
        return new CodeActionCapability.CodeAction(title, "", false, null,
                commandRefOf(command, title));
    }

    private static CodeActionCapability.CodeAction actionOf(CodeAction action) {
        String title = requiredTitle(action.getTitle(), "a code action");
        Optional<WorkspaceEdit> clientEdit = action.getCommand() == null ? Optional.empty()
                : clientEditOf(action.getCommand());
        CodeActionCapability.WorkspaceEdit edit = action.getEdit() != null ? editOf(action.getEdit())
                : clientEdit.map(LspCodeActionMapper::editOf).orElse(null);
        CodeActionCapability.CommandRef command = action.getCommand() == null || clientEdit.isPresent() ? null
                : commandRefOf(action.getCommand(), title);
        return new CodeActionCapability.CodeAction(title, action.getKind(),
                Boolean.TRUE.equals(action.getIsPreferred()), edit, command);
    }

    /**
     * The client side command {@code java.apply.workspaceEdit} names no server command: its argument is the
     * workspace edit the client applies. The edit is taken from the argument, so the command is never sent.
     *
     * @return the edit the command carries, empty when the command is not {@code java.apply.workspaceEdit}
     * @throws MalformedPayload when the command has no argument that reads as a workspace edit
     */
    private static Optional<WorkspaceEdit> clientEditOf(Command command) {
        if (!APPLY_WORKSPACE_EDIT.equals(command.getCommand())) {
            return Optional.empty();
        }
        String missing = "the command '" + APPLY_WORKSPACE_EDIT + "' carries no workspace edit";
        if (command.getArguments() == null || command.getArguments().isEmpty()) {
            throw new MalformedPayload(missing);
        }
        try {
            WorkspaceEdit edit = GSON.fromJson(GSON.toJsonTree(command.getArguments().getFirst()), WorkspaceEdit.class);
            if (edit == null) {
                throw new MalformedPayload(missing);
            }
            return Optional.of(edit);
        } catch (JsonParseException unreadable) {
            throw new MalformedPayload(missing);
        }
    }

    private static String requiredTitle(String title, String subject) {
        if (title == null || title.isBlank()) {
            throw new MalformedPayload(subject + " has no title");
        }
        return title;
    }

    private static CodeActionCapability.CommandRef commandRefOf(Command command, String title) {
        if (command.getCommand() == null || command.getCommand().isBlank()) {
            throw new MalformedPayload("the command of '" + title + "' has no identifier");
        }
        return new CodeActionCapability.CommandRef(command.getCommand(), command.getTitle(), command.getArguments());
    }

    static CodeActionCapability.WorkspaceEdit editOf(WorkspaceEdit edit) {
        if (edit == null) {
            throw new MalformedPayload("the workspace edit is empty");
        }
        List<CodeActionCapability.WorkspaceEdit.DocumentEdit> documents = new ArrayList<>();
        List<CodeActionCapability.WorkspaceEdit.UnsupportedChange> unsupported = new ArrayList<>();
        if (edit.getDocumentChanges() != null) {
            for (Either<TextDocumentEdit, ResourceOperation> change : edit.getDocumentChanges()) {
                if (change == null) {
                    throw new MalformedPayload("the workspace edit has an empty document change");
                }
                if (change.isLeft()) {
                    documentEditOf(change.getLeft(), documents, unsupported);
                } else {
                    unsupported.add(resourceChangeOf(change.getRight()));
                }
            }
        } else if (edit.getChanges() != null) {
            for (Map.Entry<String, List<TextEdit>> document : edit.getChanges().entrySet()) {
                addDocument(document.getKey(), document.getValue(), documents, unsupported);
            }
        }
        return new CodeActionCapability.WorkspaceEdit(documents, unsupported);
    }

    private static void documentEditOf(TextDocumentEdit documentEdit,
            List<CodeActionCapability.WorkspaceEdit.DocumentEdit> documents,
            List<CodeActionCapability.WorkspaceEdit.UnsupportedChange> unsupported) {
        if (documentEdit.getTextDocument() == null || documentEdit.getTextDocument().getUri() == null) {
            throw new MalformedPayload("a document change names no document");
        }
        String uri = documentEdit.getTextDocument().getUri();
        List<TextEdit> textEdits = new ArrayList<>();
        boolean snippets = false;
        for (Either<TextEdit, SnippetTextEdit> edit : documentEdit.getEdits()) {
            if (edit == null) {
                throw new MalformedPayload("a document change of '" + uri + "' has an empty edit");
            }
            if (edit.isRight()) {
                snippets = true;
            } else {
                textEdits.add(edit.getLeft());
            }
        }
        if (snippets) {
            unsupported.add(new CodeActionCapability.WorkspaceEdit.UnsupportedChange("snippet", uri));
        } else {
            addDocument(uri, textEdits, documents, unsupported);
        }
    }

    private static void addDocument(String uri, List<TextEdit> edits,
            List<CodeActionCapability.WorkspaceEdit.DocumentEdit> documents,
            List<CodeActionCapability.WorkspaceEdit.UnsupportedChange> unsupported) {
        if (uri == null || !uri.startsWith("file:")) {
            unsupported.add(new CodeActionCapability.WorkspaceEdit.UnsupportedChange("non-file", uri == null ? "" : uri));
            return;
        }
        List<CodeActionCapability.WorkspaceEdit.TextEdit> mapped = new ArrayList<>(edits.size());
        for (TextEdit edit : edits) {
            mapped.add(textEditOf(edit));
        }
        documents.add(new CodeActionCapability.WorkspaceEdit.DocumentEdit(pathOf(uri), mapped));
    }

    private static CodeActionCapability.WorkspaceEdit.TextEdit textEditOf(TextEdit edit) {
        if (edit == null || edit.getRange() == null || edit.getRange().getStart() == null
                || edit.getRange().getEnd() == null || edit.getNewText() == null) {
            throw new MalformedPayload("a text edit has no range or no new text");
        }
        Range range = edit.getRange();
        return new CodeActionCapability.WorkspaceEdit.TextEdit(range.getStart().getLine(),
                range.getStart().getCharacter(), range.getEnd().getLine(), range.getEnd().getCharacter(),
                edit.getNewText());
    }

    private static CodeActionCapability.WorkspaceEdit.UnsupportedChange resourceChangeOf(ResourceOperation operation) {
        if (operation instanceof CreateFile create) {
            return new CodeActionCapability.WorkspaceEdit.UnsupportedChange("create", create.getUri());
        }
        if (operation instanceof RenameFile rename) {
            return new CodeActionCapability.WorkspaceEdit.UnsupportedChange("rename",
                    rename.getOldUri() + " -> " + rename.getNewUri());
        }
        if (operation instanceof DeleteFile delete) {
            return new CodeActionCapability.WorkspaceEdit.UnsupportedChange("delete", delete.getUri());
        }
        return new CodeActionCapability.WorkspaceEdit.UnsupportedChange(
                operation.getKind() == null ? "resource" : operation.getKind(), "");
    }

    private static Path pathOf(String uri) {
        try {
            return Path.of(URI.create(uri));
        } catch (IllegalArgumentException | FileSystemNotFoundException invalid) {
            throw new MalformedPayload("the document '" + uri + "' is not a file URI");
        }
    }
}
