package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.layout.Size;
import dev.tamboui.layout.Rect;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinEditorTest {
    @TempDir Path project;

    @Test
    void lineNumbersUseACompactColumnAndFollowTheVisibleRows() throws IOException {
        Path file = write("Rows.java", "first line\nsecond line\nthird line\nfourth line\nfifth line\nsixth line\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(file, 0));

        Buffer initial = render(editor, 50, 6);
        assertTrue(row(initial, 1).startsWith("  1 first line"), row(initial, 1));
        assertTrue(row(initial, 2).startsWith("  2 second line"), row(initial, 2));

        editor.document().position(5, 0);
        Buffer scrolled = render(editor, 50, 6);
        assertTrue(row(scrolled, 1).contains("third line"), row(scrolled, 1));
        assertTrue(row(scrolled, 1).startsWith("  3 "), row(scrolled, 1));
    }

    private static Buffer render(BuiltinEditor editor, int width, int height) {
        Rect area = new Rect(0, 0, width, height);
        Buffer buffer = Buffer.empty(area);
        editor.element().render(Frame.forTesting(buffer), area, DefaultRenderContext.createEmpty());
        return buffer;
    }

    private static String row(Buffer buffer, int y) {
        StringBuilder result = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            result.append(buffer.get(x, y).symbol());
        }
        return result.toString().stripTrailing();
    }

    @Test
    void canonicalizesProjectAliasesForTabsAndDiagnostics() throws IOException {
        Path file = write("Alias.java", "class Alias {}\n");
        Path alias = project.resolve("project-link");
        try {
            Files.createSymbolicLink(alias, project);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = new BuiltinEditor(alias, null, themes::editorPalette);
        assertFalse(editor.open(new OpenRequest(Path.of("Alias.java"), 0)).isFailure());
        editor.setDiagnostics(List.of(new Diagnostic(alias.resolve("Alias.java"), 0, 0, 0, 1,
                Severity.ERROR, "javac", "problem")));

        assertEquals(List.of("Alias.java"), editor.tabs().stream().map(BuiltinEditor.TabView::file).toList());
        assertEquals(1, editor.diagnostics().size());
    }

    @Test
    void opensSeveralFilesAndKeepsEachDocumentAndUndoHistory() throws IOException {
        Path first = write("One.java", "class One {}\n");
        Path second = write("Two.java", "class Two {}\n");
        BuiltinEditor editor = editor(null);

        assertFalse(editor.open(new OpenRequest(first, 0)).isFailure());
        editor.insert("// changed\n");
        assertFalse(editor.open(new OpenRequest(second, 0)).isFailure());
        editor.insert("// second\n");

        editor.previousTab();
        assertEquals(first.toRealPath().toString(), editor.activeDocument().orElseThrow().file());
        assertTrue(editor.activeDocument().orElseThrow().text().startsWith("// changed\n"));
        assertTrue(editor.undo());
        assertEquals("class One {}\n", editor.activeDocument().orElseThrow().text());
        assertEquals(List.of("One.java", "Two.java"), editor.tabs().stream().map(BuiltinEditor.TabView::file).toList());
        assertTrue(editor.tabs().get(1).dirty());
    }

    @Test
    void savesInOriginalNewlineFormatAndRejectsFilesOutsideTheProject() throws IOException {
        Path file = write("Mixed.java", "class Mixed {}\r\n");
        BuiltinEditor editor = editor(null);
        assertFalse(editor.open(new OpenRequest(file, 0)).isFailure());
        editor.insert("// edited\n");

        assertFalse(editor.save().isFailure());
        assertEquals("// edited\r\nclass Mixed {}\r\n", Files.readString(file));
        assertFalse(editor.activeDocument().orElseThrow().dirty());

        Path outside = Files.createTempFile("outside", ".java");
        assertTrue(editor.open(new OpenRequest(outside, 0)).isFailure());
    }

    @Test
    void refusesToOverwriteChangesMadeOutsideTheEditor() throws IOException {
        Path file = write("Concurrent.java", "class Original {}\n");
        BuiltinEditor editor = editor(null);
        assertFalse(editor.open(new OpenRequest(file, 0)).isFailure());
        editor.insert("// editor change\n");
        Files.writeString(file, "class ExternalChange {}\n");

        assertTrue(editor.save().isFailure());
        assertTrue(editor.status().contains("changed outside the editor"));
        assertEquals("class ExternalChange {}\n", Files.readString(file));
        assertTrue(editor.activeDocument().orElseThrow().dirty());
    }

    @Test
    void rejectsMixedLineEndingsInsteadOfNormalizingThemOnSave() throws IOException {
        Path file = write("Mixed.java", "first\r\nsecond\nthird\r\n");
        BuiltinEditor editor = editor(null);

        assertTrue(editor.open(new OpenRequest(file, 0)).isFailure());
        assertTrue(editor.status().contains("mixed line endings"));
        assertEquals("first\r\nsecond\nthird\r\n", Files.readString(file));
    }

    @Test
    void keyboardEditsAreUndoableAndRedoable() throws IOException {
        Path file = write("One.java", "class One {}\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(file, 0));

        assertTrue(editor.handleKey(KeyEvent.ofChar('x')));
        assertTrue(editor.activeDocument().orElseThrow().dirty());
        assertTrue(editor.undo());
        assertEquals("class One {}\n", editor.activeDocument().orElseThrow().text());
        assertTrue(editor.redo());
        assertTrue(editor.activeDocument().orElseThrow().text().startsWith("x"));
    }

    @Test
    void keyboardLineInsertionRemapsDiagnosticsAndUndoRestoresThem() throws IOException {
        Path file = write("One.java", "class One {}\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(file, 0));
        editor.setDiagnostics(List.of(new Diagnostic(file, 0, 0, 0, 4, Severity.ERROR, "javac", "bad")));

        assertTrue(editor.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(1, editor.diagnostics().getFirst().startLine());
        assertTrue(editor.undo());
        assertEquals(0, editor.diagnostics().getFirst().startLine());
    }

    @Test
    void dirtyCloseWaitsForPilotConfirmationAndThenRemovesOnlyTheActiveTab() throws Exception {
        Path first = write("One.java", "class One {}\n");
        Path second = write("Two.java", "class Two {}\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(first, 0));
        editor.insert("// unsaved\n");
        editor.open(new OpenRequest(second, 0));
        editor.insert("// also unsaved\n");
        editor.previousTab();

        assertEquals(BuiltinEditor.CloseResult.CONFIRM_REQUIRED, editor.closeActive());
        assertTrue(editor.closeConfirmationPending());
        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(editor::element, new Size(80, 24))) {
            test.runner().eventRouter().addGlobalHandler(event -> confirmationKey(editor, event));
            Pilot pilot = test.pilot();
            pilot.pause(Duration.ofMillis(100));
            pilot.press('y');
            pilot.pause(Duration.ofMillis(150));
        }

        assertFalse(editor.closeConfirmationPending());
        assertEquals(List.of("Two.java"), editor.tabs().stream().map(BuiltinEditor.TabView::file).toList());
        assertEquals(second.toRealPath().toString(), editor.activeDocument().orElseThrow().file());
    }

    @Test
    void restoresOpenTabsAndUnsavedRecoveryTextForTheSameProject() throws IOException {
        Path first = write("One.java", "class One {}\n");
        Path second = write("Two.java", "class Two {}\n");
        Path session = project.resolve("editor.properties");
        BuiltinEditor editor = editor(session);
        editor.open(new OpenRequest(first, 0));
        editor.insert("// recovered\n");
        editor.open(new OpenRequest(second, 0));
        editor.persist();

        BuiltinEditor restored = editor(session);
        restored.restoreSession();

        assertEquals(List.of("One.java", "Two.java"), restored.tabs().stream().map(BuiltinEditor.TabView::file).toList());
        assertEquals("class Two {}\n", restored.activeDocument().orElseThrow().text());
        restored.previousTab();
        assertTrue(restored.activeDocument().orElseThrow().text().startsWith("// recovered\n"));
    }

    @Test
    void restoresDirtyRecoveryWhenBackingFileHasDisappeared() throws IOException {
        Path file = write("Missing.java", "class Missing {}\n");
        Path session = project.resolve("editor.properties");
        BuiltinEditor editor = editor(session);
        editor.open(new OpenRequest(file, 0));
        editor.insert("// recovery\n");
        editor.persist();
        Files.delete(file);

        BuiltinEditor restored = editor(session);
        restored.restoreSession();

        assertEquals(1, restored.tabs().size());
        assertTrue(restored.activeDocument().orElseThrow().dirty());
        assertTrue(restored.activeDocument().orElseThrow().text().startsWith("// recovery\n"));
        assertFalse(restored.save().isFailure());
        assertTrue(Files.readString(file).startsWith("// recovery\n"));
    }

    @Test
    void doesNotOverwriteFileThatReappearsAfterRecoveryWasRestored() throws IOException {
        Path file = write("Missing.java", "class Original {}\n");
        Path session = project.resolve("editor.properties");
        BuiltinEditor editor = editor(session);
        editor.open(new OpenRequest(file, 0));
        editor.insert("// recovery\n");
        editor.persist();
        Files.delete(file);

        BuiltinEditor restored = editor(session);
        restored.restoreSession();
        Files.writeString(file, "class Replacement {}\n");

        assertTrue(restored.save().isFailure());
        assertTrue(restored.status().contains("changed outside the editor"));
        assertEquals("class Replacement {}\n", Files.readString(file));
        assertTrue(restored.activeDocument().orElseThrow().dirty());
    }

    @Test
    void doesNotRestoreRecoveryThroughSymlinkToOutsideProject() throws IOException {
        Path file = write("Linked.java", "class Original {}\n");
        Path outside = Files.createTempFile("editor-outside", ".java");
        Files.writeString(outside, "class External {}\n");
        Path session = project.resolve("editor.properties");
        BuiltinEditor editor = editor(session);
        assertFalse(editor.open(new OpenRequest(file, 0)).isFailure());
        editor.insert("// recovery\n");
        editor.persist();
        Files.delete(file);
        try {
            Files.createSymbolicLink(file, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        BuiltinEditor restored = editor(session);
        restored.restoreSession();

        assertTrue(restored.tabs().isEmpty());
        assertEquals("class External {}\n", Files.readString(outside));
    }

    @Test
    void keepsRecoveryVisibleButRefusesSaveIfBackingFileBecameMixedNewline() throws IOException {
        Path file = write("MixedRecovery.java", "class Original {}\n");
        Path session = project.resolve("editor.properties");
        BuiltinEditor editor = editor(session);
        assertFalse(editor.open(new OpenRequest(file, 0)).isFailure());
        editor.insert("// recovery\n");
        editor.persist();
        String externalText = "class External {\r\n  void run() {}\n}\r\n";
        Files.writeString(file, externalText);

        BuiltinEditor restored = editor(session);
        restored.restoreSession();

        assertEquals(1, restored.tabs().size());
        assertTrue(restored.activeDocument().orElseThrow().dirty());
        assertTrue(restored.save().isFailure());
        assertEquals(externalText, Files.readString(file));
    }

    @Test
    void filtersDiagnosticsByFileAndMovesToTheSelectedLocation() throws IOException {
        Path first = write("One.java", "class One {\n  void run() {}\n}\n");
        Path second = write("Two.java", "class Two {}\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(first, 0));
        editor.setDiagnostics(List.of(
                new Diagnostic(first, 1, 2, 1, 6, Severity.ERROR, "javac", "bad method"),
                new Diagnostic(second, 0, 0, 0, 1, Severity.WARNING, "javac", "other file")));

        assertEquals(1, editor.diagnostics().size());
        assertTrue(editor.jumpToDiagnostic(0));
        assertEquals(1, editor.activeDocument().orElseThrow().line());
        assertEquals(2, editor.activeDocument().orElseThrow().column());
    }

    @Test
    void shiftsDiagnosticsAtTheFirstChangedRowWhenLinesAreInserted() throws IOException {
        Path file = write("One.java", "class One {\n  void run() {}\n}\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(file, 0));
        editor.setDiagnostics(List.of(
                new Diagnostic(file, 1, 2, 1, 6, Severity.ERROR, "javac", "bad method")));
        editor.document().position(1, 0);

        editor.insert("\n");

        assertEquals(2, editor.diagnostics().getFirst().startLine());
        assertTrue(editor.undo());
        assertEquals(1, editor.diagnostics().getFirst().startLine());
    }

    private Path write(String name, String contents) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, contents);
        return file;
    }

    private BuiltinEditor editor(Path session) {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        return new BuiltinEditor(project, session, themes::editorPalette);
    }

    private static EventResult confirmationKey(BuiltinEditor editor, Event event) {
        if (event instanceof KeyEvent key && editor.handleKey(key)) {
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
