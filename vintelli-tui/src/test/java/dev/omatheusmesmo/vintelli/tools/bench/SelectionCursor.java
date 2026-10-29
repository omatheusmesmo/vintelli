package dev.omatheusmesmo.vintelli.tools.bench;

/**
 * Prototype of the keyboard selection state the editor adapter in #31 keeps
 * outside {@code dev.tamboui.widgets.input.TextAreaState}, which has no
 * selection concept: an anchor captured when the selection starts plus the
 * live cursor position. Row and column are logical (row index and character
 * column), matching {@code TextAreaState} cursor semantics.
 *
 * @param anchorRow row where the selection anchor was captured
 * @param anchorCol character column of the selection anchor
 * @param cursorRow current cursor row of the selection
 * @param cursorCol current cursor character column of the selection
 */
public record SelectionCursor(int anchorRow, int anchorCol, int cursorRow, int cursorCol) {

    /**
     * A selection with anchor and cursor on the same character (a plain caret).
     *
     * @param row the cursor row
     * @param col the cursor character column
     * @return a collapsed cursor
     */
    public static SelectionCursor collapsed(int row, int col) {
        return new SelectionCursor(row, col, row, col);
    }

    /**
     * Extends the selection to a new cursor position, keeping the anchor.
     *
     * @param row the new cursor row
     * @param col the new cursor character column
     * @return a selection over the same anchor with a moved cursor
     */
    public SelectionCursor extendTo(int row, int col) {
        return new SelectionCursor(anchorRow, anchorCol, row, col);
    }

    /**
     * True when anchor and cursor coincide, so nothing is painted.
     *
     * @return true when no characters are selected
     */
    public boolean isEmpty() {
        return anchorRow == cursorRow && anchorCol == cursorCol;
    }

    /**
     * First logical row covered by the selection.
     *
     * @return the row bound where selection starts
     */
    public int startRow() {
        return Math.min(anchorRow, cursorRow);
    }

    /**
     * Last logical row covered by the selection.
     *
     * @return the row bound where selection ends
     */
    public int endRow() {
        return Math.max(anchorRow, cursorRow);
    }

    /**
     * Character column where the selection starts on {@link #startRow()}.
     *
     * @return the leading character column
     */
    public int startCol() {
        return anchorFirst() ? anchorCol : cursorCol;
    }

    /**
     * Character column where the selection ends on {@link #endRow()}.
     *
     * @return the trailing character column
     */
    public int endCol() {
        return anchorFirst() ? cursorCol : anchorCol;
    }

    private boolean anchorFirst() {
        return anchorRow < cursorRow || (anchorRow == cursorRow && anchorCol <= cursorCol);
    }
}
