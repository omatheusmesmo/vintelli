package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.UnsupportedChange;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
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

class BuiltinEditorQuickFixTest {

    private static final String TEXT = "one\ntwo\nthree\nfour\n";

    @TempDir Path project;

    @Test
    void theRequestCarriesTheCaretRangeAndTheDiagnosticsOnThatLine() throws IOException {
        Path file = write("Request.txt", TEXT);
        BuiltinEditor editor = editor();
        editor.open(new OpenRequest(file, 2));
        Diagnostic onCaret = problem(file, 2);
        editor.setDiagnostics(List.of(problem(file, 0), onCaret));

        QuickFixRequest request = editor.quickFixRequest().orElseThrow();

        assertEquals(file.toRealPath(), request.file());
        assertEquals(new SourceRange(2, 0, 2, 0), request.range());
        assertEquals(List.of(onCaret), request.diagnostics());
    }

    @Test
    void aSelectionIsTheRangeAndKeepsEveryDiagnosticOnIt() throws IOException {
        Path file = write("Selection.txt", TEXT);
        BuiltinEditor editor = editor();
        editor.open(new OpenRequest(file, 0));
        Diagnostic first = problem(file, 0);
        Diagnostic last = problem(file, 3);
        editor.setDiagnostics(List.of(first, last));
        editor.selectAll();

        QuickFixRequest request = editor.quickFixRequest().orElseThrow();

        assertEquals(new SourceRange(0, 0, 4, 0), request.range());
        assertEquals(List.of(first, last), request.diagnostics());
    }

    @Test
    void noRequestExistsWithoutAnOpenDocument() {
        assertFalse(editor().quickFixRequest().isPresent());
    }

    @Test
    void anAppliedEditIsOneUndoStepAndMarksTheDocumentDirty() throws IOException {
        Path file = write("Apply.txt", TEXT);
        BuiltinEditor editor = editor();
        editor.open(new OpenRequest(file, 1));
        QuickFixRequest request = editor.quickFixRequest().orElseThrow();
        WorkspaceEdit edit = edit(file.toRealPath(), new TextEdit(1, 0, 1, 3, "TWO"));

        WorkspaceEditApplier.Result result = editor.applyQuickFix(request, edit);

        assertInstanceOf(WorkspaceEditApplier.Applied.class, result);
        assertEquals("one\nTWO\nthree\nfour\n", editor.activeDocument().orElseThrow().text());
        assertTrue(editor.activeDocument().orElseThrow().dirty());
        assertTrue(editor.undo());
        assertEquals(TEXT, editor.activeDocument().orElseThrow().text());
        assertFalse(editor.activeDocument().orElseThrow().dirty());
        assertFalse(editor.undo(), "the quick fix is a single undo step");
    }

    @Test
    void anEditOfAnotherFileIsRejectedAndTheDocumentStaysUntouched() throws IOException {
        Path file = write("Target.txt", TEXT);
        Path other = write("Other.txt", "elsewhere\n");
        BuiltinEditor editor = editor();
        editor.open(new OpenRequest(file, 0));
        QuickFixRequest request = editor.quickFixRequest().orElseThrow();

        WorkspaceEditApplier.Result result = editor.applyQuickFix(request,
                edit(other.toRealPath(), new TextEdit(0, 0, 0, 3, "ONE")));

        assertInstanceOf(WorkspaceEditApplier.Rejected.class, result);
        assertEquals(TEXT, editor.activeDocument().orElseThrow().text());
        assertFalse(editor.activeDocument().orElseThrow().dirty());
    }

    @Test
    void aResourceOperationIsRejectedWithItsReason() throws IOException {
        Path file = write("Resource.txt", TEXT);
        BuiltinEditor editor = editor();
        editor.open(new OpenRequest(file, 0));
        WorkspaceEdit edit = new WorkspaceEdit(List.of(), List.of(new UnsupportedChange("rename", "Old.java")));

        WorkspaceEditApplier.Result result = editor.applyQuickFix(editor.quickFixRequest().orElseThrow(), edit);

        assertInstanceOf(WorkspaceEditApplier.Rejected.class, result);
        assertTrue(((WorkspaceEditApplier.Rejected) result).reason().contains("rename"));
        assertEquals(TEXT, editor.activeDocument().orElseThrow().text());
    }

    private static WorkspaceEdit edit(Path file, TextEdit text) {
        return new WorkspaceEdit(List.of(new DocumentEdit(file, List.of(text))), List.of());
    }

    private static Diagnostic problem(Path file, int line) {
        return new Diagnostic(file, line, 0, line, 1, Severity.ERROR, "test", "problem at " + line);
    }

    private Path write(String name, String contents) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, contents);
        return file;
    }

    private BuiltinEditor editor() {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        return new BuiltinEditor(project, null, themes::editorPalette);
    }
}
