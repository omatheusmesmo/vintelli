package dev.omatheusmesmo.vintelli.tui.editor;

import dev.tamboui.text.CharWidth;
import dev.tamboui.text.GraphemeClusters;

/**
 * Maps a screen cell of a clipped editor row to the character column the caret
 * takes there. The walk follows the clusters the text area draws from
 * {@code scrollCol} on, measured with {@link CharWidth} like the caret painter,
 * so a cell on the second half of a wide character selects that character.
 */
final class TextCellMap {

    private TextCellMap() {
    }

    static int charColumnAt(String line, int scrollCol, int cell) {
        int offset = Math.min(Math.max(0, scrollCol), line.length());
        int width = 0;
        while (offset < line.length()) {
            int next = GraphemeClusters.clusterEnd(line, offset);
            int clusterWidth = CharWidth.of(line.substring(offset, next));
            if (cell < width + clusterWidth) {
                return offset;
            }
            width += clusterWidth;
            offset = next;
        }
        return line.length();
    }
}
