package dev.omatheusmesmo.vintelli.tui.editor;

import dev.tamboui.text.CharWidth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextCellMapTest {

    @Test
    void asciiCellsMapToTheCharacterUnderThem() {
        assertEquals(0, TextCellMap.charColumnAt("abcdef", 0, 0));
        assertEquals(3, TextCellMap.charColumnAt("abcdef", 0, 3));
        assertEquals(6, TextCellMap.charColumnAt("abcdef", 0, 9), "a cell past the text clamps to the line end");
    }

    @Test
    void scrolledLineCountsCellsFromTheScrollColumn() {
        assertEquals(3, TextCellMap.charColumnAt("0123456789", 3, 0));
        assertEquals(5, TextCellMap.charColumnAt("0123456789", 3, 2));
    }

    @Test
    void secondCellOfAWideCharacterSelectsThatCharacter() {
        String line = "中文ab";

        assertEquals(0, TextCellMap.charColumnAt(line, 0, 0), "first cell of 中");
        assertEquals(0, TextCellMap.charColumnAt(line, 0, 1), "second cell of 中");
        assertEquals(1, TextCellMap.charColumnAt(line, 0, 2), "first cell of 文");
        assertEquals(1, TextCellMap.charColumnAt(line, 0, 3), "second cell of 文");
        assertEquals(2, TextCellMap.charColumnAt(line, 0, 4), "a follows the wide pair");
        assertEquals(4, TextCellMap.charColumnAt(line, 0, 9), "past the end clamps to the line end");
    }

    @Test
    void tabCellsAreMeasuredLikeTheCaretPainter() {
        String line = "a\tb";
        int afterTab = CharWidth.of("a\t");

        assertEquals(2, TextCellMap.charColumnAt(line, 0, afterTab), "the cell after the tab is b");
    }

    @Test
    void emptyLineAlwaysMapsToColumnZero() {
        assertEquals(0, TextCellMap.charColumnAt("", 0, 0));
        assertEquals(0, TextCellMap.charColumnAt("", 0, 7));
    }

    @Test
    void scrollColumnPastTheLineEndMapsToTheLineEnd() {
        assertEquals(3, TextCellMap.charColumnAt("abc", 9, 0));
    }
}
