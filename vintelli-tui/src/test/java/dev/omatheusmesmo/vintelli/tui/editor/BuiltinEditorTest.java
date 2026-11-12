package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
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
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        assertTrue(row(initial, 0).startsWith("  1 first line"), row(initial, 0));
        assertTrue(row(initial, 1).startsWith("  2 second line"), row(initial, 1));

        editor.document().position(5, 0);
        Buffer scrolled = render(editor, 50, 6);
        assertTrue(row(scrolled, 0).startsWith("  2 second line"), row(scrolled, 0));
        assertTrue(row(scrolled, 4).startsWith("  6 sixth line"), row(scrolled, 4));
    }

    @Test
    void constructingTheEditorDoesNotResolveTheTuiIconMapping() {
        String previous = System.getProperty("vintelli.icons.file");
        System.setProperty("vintelli.icons.file", project.resolve("missing-icons.properties").toString());
        try {
            assertDoesNotThrow(() -> editor(null));
        } finally {
            if (previous == null) {
                System.clearProperty("vintelli.icons.file");
            } else {
                System.setProperty("vintelli.icons.file", previous);
            }
        }
    }

    private static final String IMPORTS ="package demo;\n\nimport java.util.List;\nimport java.util.Map;\n\nimport static java.lang.Math.max;\n\nclass A {\n    int x;\n}\n";

    private BuiltinEditor foldedEditor() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("A.java", IMPORTS), 0));
        assertTrue(editor.toggleImportFold());
        return editor;
    }

    @Test
    void foldingCollapsesTheImportBlockIntoOnePlaceholderAndExpandsAgain() throws IOException {
        BuiltinEditor editor = foldedEditor();

        Buffer folded = render(editor, 60, 8);
        assertTrue(row(folded, 0).startsWith("   1 package demo;"), row(folded, 0));
        assertTrue(row(folded, 2).startsWith("   3 import ... (3 statements)"), row(folded, 2));
        assertTrue(row(folded, 4).startsWith("   8 class A {"), row(folded, 4));
        assertEquals(IMPORTS, editor.activeDocument().orElseThrow().text());

        assertTrue(editor.toggleImportFold());
        assertTrue(row(render(editor, 60, 12), 2).contains("import java.util.List;"));
    }

    @Test
    void editingWhileFoldedKeepsTheHiddenImportsAligned() throws IOException {
        BuiltinEditor editor = foldedEditor();
        editor.document().position(7, 0);
        assertTrue(editor.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        editor.insert("// note");

        String expected = IMPORTS.replace("class A", "\n// noteclass A");
        assertEquals(expected, editor.activeDocument().orElseThrow().text());
        assertTrue(editor.document().importsFolded());
        editor.toggleImportFold();
        assertEquals(expected, editor.activeDocument().orElseThrow().text());
        assertTrue(editor.undo());
        assertEquals(IMPORTS.replace("class A", "\nclass A"), editor.activeDocument().orElseThrow().text());
    }

    @Test
    void editingThePlaceholderExpandsTheBlockWithoutLosingImports() throws IOException {
        BuiltinEditor editor = foldedEditor();
        editor.document().position(0, 0);
        editor.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        editor.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        editor.handleKey(KeyEvent.ofChar('x'));

        assertEquals(IMPORTS, editor.activeDocument().orElseThrow().text());
        assertFalse(editor.document().importsFolded());
    }

    @Test
    void goToLineMovesTheCaretAndTheStatusBarPositionTogether() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Target.txt", "alpha\nbeta\ngamma\n"), 0));

        assertTrue(editor.goToLine(3, 2));

        assertEquals(2, editor.document().row());
        assertEquals(1, editor.document().column());
        BuiltinEditor.StatusBarInfo bar = editor.statusBarInfo().orElseThrow();
        assertEquals(3, bar.line());
        assertEquals(2, bar.column());
        assertEquals("Line 3, column 2", editor.status());
    }

    @Test
    void goToLineClampsPastTheLastLineAndItsLength() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Clamp.txt", "alpha\nbeta"), 0));

        editor.goToLine(999, 999);

        assertEquals(1, editor.document().row());
        assertEquals(4, editor.document().column());
        assertEquals(2, editor.statusBarInfo().orElseThrow().line());
        assertEquals(5, editor.statusBarInfo().orElseThrow().column());
    }

    @Test
    void goToLineClampsTheColumnToItsOwnLine() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Column.txt", "ab\nlonger line\n"), 0));

        editor.goToLine(1, 50);

        assertEquals(0, editor.document().row());
        assertEquals(2, editor.document().column());
    }

    @Test
    void goToLineWithoutAnOpenFileSaysSoAndMovesNothing() {
        BuiltinEditor editor = editor(null);

        assertFalse(editor.goToLine(4, 1));
        assertEquals("No file is open", editor.status());
    }

    @Test
    void goToLineBelowAFoldedImportBlockKeepsTheBlockFoldedAndPlacesTheView() throws IOException {
        BuiltinEditor editor = foldedEditor();

        editor.goToLine(9, 5);

        assertTrue(editor.document().importsFolded());
        assertEquals(8, editor.document().row());
        assertEquals(4, editor.document().column());
        assertEquals(editor.document().viewRow(8), editor.document().state().cursorRow());
        assertEquals(9, editor.statusBarInfo().orElseThrow().line());
        assertEquals(5, editor.statusBarInfo().orElseThrow().column());
    }

    @Test
    void goToLineInsideAFoldedImportBlockExpandsTheBlockToReachIt() throws IOException {
        BuiltinEditor editor = foldedEditor();

        editor.goToLine(4, 1);

        assertFalse(editor.document().importsFolded());
        assertEquals(3, editor.document().row());
        assertEquals(4, editor.statusBarInfo().orElseThrow().line());
    }

    @Test
    void goToLineScrollsTheViewToTheTargetRow() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= 80; line++) {
            text.append("line ").append(line).append('\n');
        }
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Long.txt", text.toString()), 0));
        render(editor, 50, 6);

        editor.goToLine(60, 1);
        Buffer scrolled = render(editor, 50, 6);

        boolean targetVisible = false;
        for (int y = 0; y < 6; y++) {
            targetVisible |= row(scrolled, y).contains("60 line 60");
        }
        assertTrue(targetVisible, "the target row is brought into view");
    }

    @Test
    void cursorMovesBetweenViewRowsAndDocumentRows() throws IOException {
        BuiltinEditor editor = foldedEditor();
        editor.document().position(8, 4);
        assertEquals(5, editor.document().state().cursorRow());
        assertEquals(8, editor.document().row());

        editor.handleKey(KeyEvent.ofKey(KeyCode.UP));
        editor.handleKey(KeyEvent.ofKey(KeyCode.UP));
        assertEquals(6, editor.document().row());
        editor.handleKey(KeyEvent.ofKey(KeyCode.UP));
        assertEquals(2, editor.document().row());
        assertEquals(2, editor.document().state().cursorRow());
    }

    @Test
    void jumpingIntoTheHiddenBlockExpandsIt() throws IOException {
        BuiltinEditor editor = foldedEditor();
        editor.document().position(3, 2);
        assertFalse(editor.document().importsFolded());
        assertEquals(3, editor.document().row());
    }

    @Test
    void selectionsMapBetweenViewAndDocumentRows() {
        ImportFold fold = new ImportFold(2, 4, 3);
        SelectionCursor selection = SelectionCursor.collapsed(7, 3).extendTo(8, 1);

        assertEquals(new SelectionCursor(4, 3, 5, 1), fold.toView(selection));
        assertEquals(selection, fold.toDocument(fold.toView(selection)));
        assertEquals(new SelectionCursor(2, 0, 5, 2), fold.toView(new SelectionCursor(3, 5, 8, 2)));
    }

    @Test
    void foldStateStaysWithItsTabAcrossTabSwitches() throws IOException {
        BuiltinEditor editor = foldedEditor();
        editor.open(new OpenRequest(write("B.java", IMPORTS), 0));
        assertFalse(editor.document().importsFolded());

        editor.previousTab();
        assertTrue(editor.document().importsFolded());
        editor.nextTab();
        assertFalse(editor.document().importsFolded());
    }

    @Test
    void foldingReportsWhenThereIsNothingToFold() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Plain.java", "class Plain {}\n"), 0));

        assertFalse(editor.toggleImportFold());
        assertEquals("No import block to fold", editor.status());
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
    void duplicatesTheCaretLineBelowItAndMovesTheCaretToTheCopyInTheSameColumn() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Middle.txt", "alpha\nbeta\ngamma\n"), 0));
        editor.document().position(1, 2);

        editor.duplicateLine();

        assertEquals("alpha\nbeta\nbeta\ngamma\n", editor.activeDocument().orElseThrow().text());
        assertEquals(2, editor.document().row());
        assertEquals(2, editor.document().column());
    }

    @Test
    void duplicatesTheFirstLineWithTheCaretOnTheCopy() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("First.txt", "alpha\nbeta\n"), 0));

        editor.duplicateLine();

        assertEquals("alpha\nalpha\nbeta\n", editor.activeDocument().orElseThrow().text());
        assertEquals(1, editor.document().row());
        assertEquals(0, editor.document().column());
    }

    @Test
    void duplicatesTheLastLineWhenTheFileEndsWithANewline() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Newline.txt", "alpha\nbeta\ngamma\n"), 2));

        editor.duplicateLine();

        assertEquals("alpha\nbeta\ngamma\ngamma\n", editor.activeDocument().orElseThrow().text());
        assertEquals(3, editor.document().row());
    }

    @Test
    void duplicatingTheLastLineWithoutANewlineGivesTheCopyTheBreakAndKeepsTheEndAsItWas() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("NoNewline.txt", "alpha\nbeta"), 1));
        editor.document().position(1, 3);

        editor.duplicateLine();

        assertEquals("alpha\nbeta\nbeta", editor.activeDocument().orElseThrow().text());
        assertEquals(2, editor.document().row());
        assertEquals(3, editor.document().column());
    }

    @Test
    void duplicateIsOneUndoStepThatRemovesTheCopyAndMarksTheDocumentDirty() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Undo.txt", "alpha\nbeta\n"), 0));
        editor.document().position(1, 1);

        editor.duplicateLine();
        assertTrue(editor.activeDocument().orElseThrow().dirty());

        assertTrue(editor.undo());
        assertEquals("alpha\nbeta\n", editor.activeDocument().orElseThrow().text());
        assertEquals(1, editor.document().row());
        assertEquals(1, editor.document().column());
        assertFalse(editor.activeDocument().orElseThrow().dirty());
        assertFalse(editor.undo(), "the copy takes exactly one undo step");
    }

    @Test
    void duplicateKeepsTheImportBlockFoldedAndTheDocumentIntact() throws IOException {
        BuiltinEditor editor = foldedEditor();
        editor.document().position(8, 4);

        editor.duplicateLine();

        assertTrue(editor.document().importsFolded());
        assertEquals(IMPORTS.replace("    int x;\n", "    int x;\n    int x;\n"),
                editor.activeDocument().orElseThrow().text());
        assertEquals(9, editor.document().row());
    }

    @Test
    void duplicateKeepsDiagnosticsOnTheirLinesAndShiftsTheOnesBelowTheCopy() throws IOException {
        Path file = write("Diagnostics.txt", "alpha\nbeta\ngamma\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(file, 0));
        editor.setDiagnostics(List.of(new Diagnostic(file, 2, 0, 2, 5, Severity.ERROR, "javac", "bad")));

        editor.duplicateLine();
        assertEquals(3, editor.diagnostics().getFirst().startLine());

        assertTrue(editor.undo());
        assertEquals(2, editor.diagnostics().getFirst().startLine());
    }

    @Test
    void duplicateWithASelectionCopiesOnlyTheCaretLineAndClearsTheSelection() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Selected.txt", "alpha\nbeta\n"), 0));
        editor.document().pointerPress(0, 1);
        editor.document().pointerDrag(0, 4);
        assertTrue(editor.document().hasSelection());

        editor.duplicateLine();

        assertEquals("alpha\nalpha\nbeta\n", editor.activeDocument().orElseThrow().text());
        assertFalse(editor.document().hasSelection());
    }

    @Test
    void ctrlDIsLeftToTheShellEvenWhileTheFindRowIsOpen() throws IOException {
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(write("Find.txt", "alpha\n"), 0));
        editor.openFind();

        assertFalse(editor.handleKey(KeyEvent.ofChar('d', KeyModifiers.CTRL)),
                "Ctrl+D is a shell chord, the editor must not consume it");
        assertEquals("alpha\n", editor.activeDocument().orElseThrow().text());
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
    void persistsBreakpointsPerProjectAndKeepsTheirLineNumbersAcrossEditAndUndo() throws IOException {
        Path source = write("Breakpoint.java", "class Breakpoint {\n  void run() {}\n}\n");
        Path removed = write("Removed.java", "class Removed {}\n");
        Path session = project.resolve("editor.properties");
        BuiltinEditor editor = editor(session);
        editor.open(new OpenRequest(source, 0));

        assertTrue(editor.toggleBreakpoint(1));
        editor.insert("// inserted above the source\n");
        assertTrue(editor.breakpoints(editor.document()).containsKey(1));
        assertTrue(editor.undo());
        assertTrue(editor.breakpoints(editor.document()).containsKey(1));
        editor.open(new OpenRequest(removed, 0));
        assertTrue(editor.toggleBreakpoint(0));
        assertTrue(editor.toggleBreakpoint(0));

        BuiltinEditor restored = editor(session);
        restored.restoreSession();
        restored.open(new OpenRequest(source, 0));
        assertTrue(restored.breakpoints(restored.document()).containsKey(1));
        assertEquals(DebugCapability.BreakpointState.PENDING,
                restored.breakpoints(restored.document()).get(1).state());
        assertTrue(restored.sourceBreakpoints().containsKey(removed.toRealPath()));
        assertTrue(restored.sourceBreakpoints().get(removed.toRealPath()).isEmpty(),
                "empty source sets persist so a later provider reconciliation can clear removed breakpoints");
    }

    @Test
    void removesBreakpointsWhenEditsMakeTheirLineUnavailable() throws IOException {
        Path source = write("Shortened.java", "first\nsecond\nthird\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(source, 0));
        assertTrue(editor.toggleBreakpoint(2));

        editor.document().mutate(() -> editor.document().state().setText("first\n"));
        editor.changed();

        assertTrue(editor.breakpoints(editor.document()).isEmpty());
        assertTrue(editor.sourceBreakpoints().containsKey(source.toRealPath()));
        assertTrue(editor.sourceBreakpoints().get(source.toRealPath()).isEmpty());
        editor.undo();
        assertTrue(editor.breakpoints(editor.document()).isEmpty(), "undo does not resurrect a truncated breakpoint");
    }

    @Test
    void gutterClickTogglesTheVisibleLineWithoutMovingCaretOrSelection() throws IOException {
        Path source = write("Gutter.java", "first\nsecond\nthird\n");
        BuiltinEditor editor = editor(null);
        editor.open(new OpenRequest(source, 0));
        render(editor, 40, 6);
        BuiltinEditor.PointerState before = editor.pointerState().orElseThrow();

        assertTrue(editor.handlePointer(MouseEvent.press(MouseButton.LEFT, 1, 1)));

        assertTrue(editor.breakpoints(editor.document()).containsKey(1));
        BuiltinEditor.PointerState after = editor.pointerState().orElseThrow();
        assertEquals(before.line(), after.line());
        assertEquals(before.column(), after.column());
        assertEquals(before.selectedText(), after.selectedText());
    }

    @Test
    void rendersBreakpointMarkersInAsciiAndUnicodeIconModes() throws IOException {
        String previous = System.getProperty("vintelli.icons");
        try {
            System.setProperty("vintelli.icons", "ascii");
            BuiltinEditor ascii = editor(null);
            ascii.open(new OpenRequest(write("Ascii.java", "class Ascii {}\n"), 0));
            ascii.toggleBreakpoint(0);
            assertTrue(row(render(ascii, 30, 4), 0).startsWith("o 1"));

            System.setProperty("vintelli.icons", "nerd");
            BuiltinEditor unicode = editor(null);
            unicode.open(new OpenRequest(write("Unicode.java", "class Unicode {}\n"), 0));
            unicode.toggleBreakpoint(0);
            assertTrue(row(render(unicode, 30, 4), 0).startsWith("◌ 1"));
        } finally {
            if (previous == null) {
                System.clearProperty("vintelli.icons");
            } else {
                System.setProperty("vintelli.icons", previous);
            }
        }
    }

    @Test
    void executionMarkerIsIndependentFromBreakpointsInAsciiAndUnicodeModes() throws IOException {
        String previous = System.getProperty("vintelli.icons");
        String sourceText = "class Stepping {\n  void run() {}\n}\n";
        Path source = write("Stepping.java", sourceText);
        try {
            System.setProperty("vintelli.icons", "ascii");
            BuiltinEditor ascii = editor(null);
            ascii.open(new OpenRequest(source, 0));
            ascii.toggleBreakpoint(1);
            ascii.updateExecutionLocation(new DebugCapability.DebugFrame("main", source.toString(), 2, 1));
            assertTrue(row(render(ascii, 30, 4), 1).startsWith("o>2"));
            assertEquals(sourceText, ascii.activeDocument().orElseThrow().text());
            assertEquals(1, ascii.activeDocument().orElseThrow().line());
            ascii.updateExecutionLocation(null);
            assertTrue(row(render(ascii, 30, 4), 1).startsWith("o 2"));

            System.setProperty("vintelli.icons", "nerd");
            BuiltinEditor unicode = editor(null);
            unicode.open(new OpenRequest(source, 0));
            unicode.toggleBreakpoint(1);
            unicode.updateExecutionLocation(new DebugCapability.DebugFrame("main", source.toString(), 2, 1));
            assertTrue(row(render(unicode, 30, 4), 1).startsWith("◌▶2"));
            assertEquals(sourceText, unicode.activeDocument().orElseThrow().text());
            assertEquals(1, unicode.activeDocument().orElseThrow().line());
            unicode.updateExecutionLocation(null);
            assertTrue(row(render(unicode, 30, 4), 1).startsWith("◌ 2"));
        } finally {
            if (previous == null) System.clearProperty("vintelli.icons");
            else System.setProperty("vintelli.icons", previous);
        }
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
