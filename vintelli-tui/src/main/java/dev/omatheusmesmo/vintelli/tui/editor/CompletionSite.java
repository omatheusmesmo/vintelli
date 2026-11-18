package dev.omatheusmesmo.vintelli.tui.editor;

import java.nio.file.Path;

/**
 * The state of the active document a completion is asked for: the file, its whole text and the caret in
 * document rows and UTF-16 columns. Two sites are equal when the text and the caret are, so a response is
 * stale as soon as either one differs from the site it was asked for.
 *
 * @param file the open document
 * @param text the whole text of the document, including unsaved edits
 * @param row zero based caret row
 * @param column zero based caret column
 */
public record CompletionSite(Path file, String text, int row, int column) {

    /**
     * @return the text of the caret row without its line separator
     */
    public String line() {
        return lineOf(text, row);
    }

    /**
     * @return the column where the identifier that ends at the caret starts, the caret column when no
     *         identifier ends there
     */
    public int identifierStart() {
        String line = line();
        int start = Math.min(column, line.length());
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    static String lineOf(String text, int row) {
        int start = 0;
        for (int line = 0; line < row; line++) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) {
                return "";
            }
            start = newline + 1;
        }
        int end = text.indexOf('\n', start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}
