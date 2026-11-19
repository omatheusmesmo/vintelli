package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.UnsupportedChange;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rename applied to the editor: the declaration of an open file and the call in a file that is not open change
 * together, every document is one undo step of its own, and an edit the editor cannot apply completely changes no
 * document at all.
 */
class BuiltinEditorRenameTest {

    private static final String DECLARATION = "class Greeter {\n    void greet() {}\n}\n";
    private static final String CALLER = "class Caller {\n    void run() { greet(); }\n}\n";
    private static final String RENAMED_DECLARATION = "class Greeter {\n    void salute() {}\n}\n";

    @TempDir
    Path project;

    @Test
    void aRenameChangesTheOpenDeclarationAndTheClosedCallerAsOneUndoStepEach() throws IOException {
        Path greeter = write("Greeter.java", DECLARATION);
        Path caller = write("Caller.java", CALLER);
        BuiltinEditor editor = editor();
        editor.open(new OpenRequest(greeter, 1));

        WorkspaceEditApplier.Result result = editor.applyRename(new WorkspaceEdit(List.of(
                new DocumentEdit(greeter, List.of(new TextEdit(1, 9, 1, 14, "salute"))),
                new DocumentEdit(caller, List.of(new TextEdit(1, 17, 1, 22, "salute")))), List.of()));

        assertEquals(new WorkspaceEditApplier.Applied(2), result);
        assertEquals(RENAMED_DECLARATION, editor.activeDocument().orElseThrow().text());
        assertTrue(editor.activeDocument().orElseThrow().dirty());
        assertEquals(List.of("Greeter.java", "Caller.java"), editor.tabs().stream().map(tab -> tab.file()).toList());
        assertTrue(editor.tabs().get(1).dirty(), "the caller is opened dirty, ready for review before saving");

        assertTrue(editor.undo());
        assertEquals(DECLARATION, editor.activeDocument().orElseThrow().text());
        assertFalse(editor.undo(), "the declaration change is one undo step");

        editor.selectTab(1);
        assertTrue(editor.undo());
        assertEquals(CALLER, editor.activeDocument().orElseThrow().text());
        assertFalse(editor.undo(), "the caller change is one undo step of its own");
    }

    @Test
    void aFileRenameInTheEditIsRefusedAndNoDocumentChanges() throws IOException {
        Path greeter = write("Greeter.java", DECLARATION);
        Path caller = write("Caller.java", CALLER);
        BuiltinEditor editor = editor();
        editor.open(new OpenRequest(greeter, 1));

        WorkspaceEditApplier.Result result = editor.applyRename(new WorkspaceEdit(List.of(
                new DocumentEdit(greeter, List.of(new TextEdit(1, 9, 1, 14, "salute"))),
                new DocumentEdit(caller, List.of(new TextEdit(1, 17, 1, 22, "salute")))),
                List.of(new UnsupportedChange("rename", "Greeter.java -> Salute.java"))));

        assertInstanceOf(WorkspaceEditApplier.Rejected.class, result);
        String reason = ((WorkspaceEditApplier.Rejected) result).reason();
        assertTrue(reason.contains("rename"), reason);
        assertTrue(reason.endsWith("nothing was changed"), reason);
        assertEquals(DECLARATION, editor.activeDocument().orElseThrow().text());
        assertFalse(editor.activeDocument().orElseThrow().dirty());
        assertEquals(1, editor.tabs().size(), "the caller is not opened by a refused edit");
    }

    @Test
    void anEditThatDoesNotFitOneOfItsDocumentsChangesNoDocument() throws IOException {
        Path greeter = write("Greeter.java", DECLARATION);
        Path caller = write("Caller.java", CALLER);
        BuiltinEditor editor = editor();
        editor.open(new OpenRequest(greeter, 1));

        WorkspaceEditApplier.Result result = editor.applyRename(new WorkspaceEdit(List.of(
                new DocumentEdit(greeter, List.of(new TextEdit(1, 9, 1, 14, "salute"))),
                new DocumentEdit(caller, List.of(new TextEdit(9, 0, 9, 4, "salute")))), List.of()));

        assertInstanceOf(WorkspaceEditApplier.Rejected.class, result);
        assertEquals(DECLARATION, editor.activeDocument().orElseThrow().text());
        assertEquals(1, editor.tabs().size(), "the caller is not opened by a refused edit");
    }

    private Path write(String name, String contents) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, contents);
        return file.toRealPath();
    }

    private BuiltinEditor editor() {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        return new BuiltinEditor(project, null, themes::editorPalette);
    }
}
