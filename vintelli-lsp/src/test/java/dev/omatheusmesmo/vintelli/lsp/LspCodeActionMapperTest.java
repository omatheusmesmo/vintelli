package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionDisabled;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

class LspCodeActionMapperTest {

    private static final String GREETER_URI = "file:///work/Greeter.java";
    private static final Path GREETER = Path.of(URI.create(GREETER_URI));

    @Test
    void aBareCommandBecomesACommandOnlyAction() {
        List<CodeActionCapability.CodeAction> actions = LspCodeActionMapper.codeActionsOf(
                List.of(Either.forLeft(new Command("Organize the fake imports", "fake.organize"))));

        assertEquals(1, actions.size());
        CodeActionCapability.CodeAction action = actions.getFirst();
        assertEquals("Organize the fake imports", action.title());
        assertEquals("", action.kind());
        assertFalse(action.preferred());
        assertNull(action.edit());
        assertEquals(new CodeActionCapability.CommandRef("fake.organize", "Organize the fake imports"),
                action.command());
    }

    @Test
    void aCodeActionWithoutEditKeepsItsKindAndPreference() {
        CodeAction bare = new CodeAction("Suppress the warning");
        bare.setKind("quickfix");
        bare.setIsPreferred(true);

        CodeActionCapability.CodeAction action = LspCodeActionMapper.codeActionsOf(
                List.of(Either.forRight(bare))).getFirst();

        assertEquals("Suppress the warning", action.title());
        assertEquals("quickfix", action.kind());
        assertTrue(action.preferred());
        assertNull(action.edit());
        assertNull(action.command());
    }

    @Test
    void theChangesMapBecomesTextEditsPerDocument() {
        CodeAction fix = new CodeAction("Add the import");
        fix.setEdit(new WorkspaceEdit(Map.of(GREETER_URI, List.of(
                new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "import java.util.List;\n")))));

        CodeActionCapability.WorkspaceEdit edit = LspCodeActionMapper.codeActionsOf(List.of(Either.forRight(fix))).getFirst().edit();

        assertEquals(List.of(new CodeActionCapability.WorkspaceEdit.DocumentEdit(GREETER, List.of(
                new CodeActionCapability.WorkspaceEdit.TextEdit(0, 0, 0, 0, "import java.util.List;\n")))), edit.documents());
        assertTrue(edit.unsupported().isEmpty());
    }

    @Test
    void documentChangesTakePrecedenceAndResourceOperationsAreUnsupported() {
        CodeAction fix = new CodeAction("Create and fill the class");
        fix.setEdit(new WorkspaceEdit(List.of(
                Either.forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(GREETER_URI, 1),
                        List.of(Either.forLeft(new TextEdit(new Range(new Position(1, 0), new Position(1, 0)),
                                "x"))))),
                Either.forRight(new CreateFile("file:///work/Missing.java")),
                Either.forRight(new RenameFile("file:///work/Old.java", "file:///work/New.java")))));
        fix.getEdit().setChanges(Map.of(GREETER_URI, List.of()));

        CodeActionCapability.WorkspaceEdit edit = LspCodeActionMapper.codeActionsOf(List.of(Either.forRight(fix)))
                .getFirst().edit();

        assertEquals(1, edit.documents().size());
        assertEquals(List.of(
                new CodeActionCapability.WorkspaceEdit.UnsupportedChange("create", "file:///work/Missing.java"),
                new CodeActionCapability.WorkspaceEdit.UnsupportedChange("rename",
                        "file:///work/Old.java -> file:///work/New.java")), edit.unsupported());
    }

    @Test
    void disabledActionsAreLeftOut() {
        CodeAction disabled = new CodeAction("Not applicable here");
        disabled.setDisabled(new CodeActionDisabled("the cursor is not on a fixable symbol"));

        assertTrue(LspCodeActionMapper.codeActionsOf(List.of(Either.forRight(disabled))).isEmpty());
        assertTrue(LspCodeActionMapper.codeActionsOf(null).isEmpty());
    }

    @Test
    void aMissingTitleIsMalformed() {
        CodeAction untitled = new CodeAction();

        assertThrows(LspCodeActionMapper.MalformedPayload.class,
                () -> LspCodeActionMapper.codeActionsOf(List.of(Either.forRight(untitled))));
    }

    @Test
    void aTextEditWithoutARangeIsMalformed() {
        TextEdit broken = new TextEdit();
        broken.setNewText("import java.util.List;\n");
        CodeAction fix = new CodeAction("Add the import without a range");
        fix.setEdit(new WorkspaceEdit(Map.of(GREETER_URI, List.of(broken))));

        assertThrows(LspCodeActionMapper.MalformedPayload.class,
                () -> LspCodeActionMapper.codeActionsOf(List.of(Either.forRight(fix))));
    }

    @Test
    void aNonFileDocumentIsUnsupportedInsteadOfGuessedAsAPath() {
        CodeAction fix = new CodeAction("Edit the library source");
        fix.setEdit(new WorkspaceEdit(Map.of("jar:file:///lib/a.jar!/A.java", List.of(
                new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "x")))));

        CodeActionCapability.WorkspaceEdit edit = LspCodeActionMapper.codeActionsOf(List.of(Either.forRight(fix))).getFirst().edit();

        assertTrue(edit.documents().isEmpty());
        assertEquals(List.of(new CodeActionCapability.WorkspaceEdit.UnsupportedChange("non-file", "jar:file:///lib/a.jar!/A.java")),
                edit.unsupported());
    }
}
