package dev.omatheusmesmo.vintelli.tui.editor;

import dev.tamboui.layout.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorElementDragScrollTest {

    private static final Rect BODY = new Rect(4, 5, 40, 10);

    @Test
    void aDragAboveTheTextScrollsOneRowUp() {
        assertEquals(-1, EditorElement.dragScrollRows(4, BODY));
        assertEquals(-1, EditorElement.dragScrollRows(0, BODY));
    }

    @Test
    void aDragBelowTheTextScrollsOneRowDown() {
        assertEquals(1, EditorElement.dragScrollRows(15, BODY));
        assertEquals(1, EditorElement.dragScrollRows(40, BODY));
    }

    @Test
    void aDragInsideTheTextDoesNotScroll() {
        assertEquals(0, EditorElement.dragScrollRows(5, BODY));
        assertEquals(0, EditorElement.dragScrollRows(14, BODY));
    }

    @Test
    void nothingScrollsWhenTheTextAreaIsNotRendered() {
        assertEquals(0, EditorElement.dragScrollRows(0, Rect.ZERO));
        assertEquals(0, EditorElement.dragScrollRows(9, Rect.ZERO));
    }
}
