package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.UnsupportedChange;
import org.junit.jupiter.api.Test;

class WorkspaceEditApplierTest {

    private static final Path GREETER = Path.of("Greeter.java");

    @Test
    void aSingleEditReplacesTheRangeMarksTheDocumentDirtyAndUndoesInOneStep() {
        EditorDocument document = new EditorDocument(GREETER, "class Greeter {}\n");

        WorkspaceEditApplier.Result result = WorkspaceEditApplier.apply(document, documentEdit(
                new TextEdit(0, 6, 0, 13, "Hello")));

        assertEquals(new WorkspaceEditApplier.Applied(1), result);
        assertEquals("class Hello {}\n", document.text());
        assertTrue(document.dirty());
        assertTrue(document.undo());
        assertEquals("class Greeter {}\n", document.text());
        assertFalse(document.undo());
    }

    @Test
    void severalEditsInOneLineAreAppliedRegardlessOfTheirOrder() {
        String text = "a = b + c;\n";

        String applied = WorkspaceEditApplier.applyTo(text, List.of(
                new TextEdit(0, 4, 0, 5, "x"),
                new TextEdit(0, 0, 0, 1, "y"),
                new TextEdit(0, 8, 0, 9, "z")));

        assertEquals("y = x + z;\n", applied);
    }

    @Test
    void multiLineEditsAreAppliedFromTheEndSoEarlierOffsetsStayValid() {
        String text = "first\nsecond\nthird\n";

        String applied = WorkspaceEditApplier.applyTo(text, List.of(
                new TextEdit(0, 0, 1, 6, "one line"),
                new TextEdit(2, 0, 2, 5, "3rd")));

        assertEquals("one line\n3rd\n", applied);
    }

    @Test
    void insertsAtTheSamePositionKeepTheOrderTheServerSentThem() {
        String applied = WorkspaceEditApplier.applyTo("ab", List.of(
                new TextEdit(0, 1, 0, 1, "X"),
                new TextEdit(0, 1, 0, 1, "Y")));

        assertEquals("aXYb", applied);
    }

    @Test
    void columnsCountUtf16CodeUnitsSoASurrogatePairIsTwoColumns() {
        EditorDocument document = new EditorDocument(GREETER, "😀 x\n");

        WorkspaceEditApplier.Result result = WorkspaceEditApplier.apply(document, documentEdit(
                new TextEdit(0, 3, 0, 4, "y")));

        assertEquals(new WorkspaceEditApplier.Applied(1), result);
        assertEquals("😀 y\n", document.text());
    }

    @Test
    void theDocumentChangesOfOneDocumentAreAppliedTogether() {
        EditorDocument document = new EditorDocument(GREETER, "one two\n");
        WorkspaceEdit edit = new WorkspaceEdit(List.of(
                new DocumentEdit(GREETER, List.of(new TextEdit(0, 0, 0, 3, "1"))),
                new DocumentEdit(GREETER.toAbsolutePath(), List.of(new TextEdit(0, 4, 0, 7, "2")))),
                List.of());

        assertEquals(new WorkspaceEditApplier.Applied(2), WorkspaceEditApplier.apply(document, edit));
        assertEquals("1 2\n", document.text());
    }

    @Test
    void resourceOperationsAreRejectedAndLeaveTheDocumentUntouched() {
        EditorDocument document = new EditorDocument(GREETER, "class Greeter {}\n");
        WorkspaceEdit edit = new WorkspaceEdit(
                List.of(new DocumentEdit(GREETER, List.of(new TextEdit(0, 0, 0, 0, "import x;\n")))),
                List.of(new UnsupportedChange("create", "file:///work/Missing.java")));

        WorkspaceEditApplier.Result result = WorkspaceEditApplier.apply(document, edit);

        WorkspaceEditApplier.Rejected rejected = assertInstanceOf(WorkspaceEditApplier.Rejected.class, result);
        assertTrue(rejected.reason().contains("create"), rejected.reason());
        assertTrue(rejected.reason().contains("Missing.java"), rejected.reason());
        assertEquals("class Greeter {}\n", document.text());
        assertFalse(document.dirty());
    }

    @Test
    void editsOfAnotherFileAreRejected() {
        EditorDocument document = new EditorDocument(GREETER, "class Greeter {}\n");
        WorkspaceEdit edit = new WorkspaceEdit(List.of(new DocumentEdit(Path.of("Other.java"),
                List.of(new TextEdit(0, 0, 0, 0, "x")))), List.of());

        WorkspaceEditApplier.Result result = WorkspaceEditApplier.apply(document, edit);

        assertInstanceOf(WorkspaceEditApplier.Rejected.class, result);
        assertEquals("class Greeter {}\n", document.text());
    }

    @Test
    void overlappingEditsAreRejectedAsAWholeAndNothingIsApplied() {
        EditorDocument document = new EditorDocument(GREETER, "abcdef\n");

        WorkspaceEditApplier.Result result = WorkspaceEditApplier.apply(document, new WorkspaceEdit(List.of(
                new DocumentEdit(GREETER, List.of(new TextEdit(0, 0, 0, 4, "x"), new TextEdit(0, 2, 0, 5, "y")))),
                List.of()));

        assertInstanceOf(WorkspaceEditApplier.Rejected.class, result);
        assertEquals("abcdef\n", document.text());
        assertFalse(document.dirty());
    }

    @Test
    void aRangeOutsideTheDocumentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceEditApplier.applyTo("one\n",
                List.of(new TextEdit(5, 0, 5, 1, "x"))));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceEditApplier.applyTo("one\n",
                List.of(new TextEdit(0, 2, 0, 1, "x"))));
    }

    @Test
    void aColumnPastTheEndOfTheLineClampsToTheLineEnd() {
        assertEquals("one!\n", WorkspaceEditApplier.applyTo("one\n", List.of(new TextEdit(0, 9, 0, 9, "!"))));
    }

    private static WorkspaceEdit documentEdit(TextEdit... edits) {
        return new WorkspaceEdit(List.of(new DocumentEdit(GREETER, List.of(edits))), List.of());
    }
}
