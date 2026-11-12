package dev.omatheusmesmo.vintelli.tui.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSelectionEditTest {

    private static final String SOURCE = "package demo;\n\nimport java.util.List;\nimport java.util.Map;\n\nimport static java.lang.Math.max;\n\nclass A {\n    int x;\n}\n";

    @Test
    void selectionInTheFoldedViewReplacesTheDocumentTextAndKeepsTheFold() {
        EditorDocument document = new EditorDocument(Path.of("A.java"), SOURCE);
        assertTrue(document.toggleImportFold());

        document.pointerPress(4, 0);
        document.pointerDrag(4, 5);
        assertEquals("class", document.selectedText(), "the selection is read in document coordinates");

        document.replaceSelection("record");

        assertEquals(SOURCE.replace("class A", "record A"), document.text());
        assertTrue(document.importsFolded(), "replacing text outside the import block keeps it folded");
        assertFalse(document.hasSelection(), "the selection is gone after the edit");
    }

    @Test
    void replacingASelectionIsOneUndoStep() {
        EditorDocument document = new EditorDocument(Path.of("A.java"), SOURCE);
        document.pointerPress(7, 0);
        document.pointerDrag(7, 5);

        document.replaceSelection("record");
        assertEquals(SOURCE.replace("class A", "record A"), document.text());

        assertTrue(document.undo());
        assertEquals(SOURCE, document.text());
        assertFalse(document.undo(), "a second undo has nothing left from the replacement");
    }

    @Test
    void pasteWithoutASelectionInsertsAtTheCaret() {
        EditorDocument document = new EditorDocument(Path.of("A.java"), "ab\n");
        document.position(0, 1);

        document.replaceSelection("漢字");

        assertEquals("a漢字b\n", document.text());
        assertEquals(3, document.column(), "the caret follows the inserted text");
    }
}
