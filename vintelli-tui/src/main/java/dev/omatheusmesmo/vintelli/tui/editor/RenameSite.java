package dev.omatheusmesmo.vintelli.tui.editor;

import java.nio.file.Path;

/**
 * What one rename request is asked for: the open file, its text with unsaved edits, the caret in zero based rows and
 * UTF-16 columns, and the identifier under the caret, which the rename input starts from when the server suggests no
 * name.
 *
 * @param file the open document the symbol is in
 * @param content the text of the document, the text the server must see
 * @param line zero based row of the caret
 * @param column zero based column of the caret in UTF-16 code units
 * @param word the identifier under or before the caret, empty when there is none
 */
public record RenameSite(Path file, String content, int line, int column, String word) {

    public static RenameSite at(Path file, String content, int line, int column) {
        String[] lines = content.split("\n", -1);
        String row = line < lines.length ? lines[line] : "";
        return new RenameSite(file, content, line, column, wordAt(row, column));
    }

    private static String wordAt(String row, int column) {
        int caret = Math.clamp(column, 0, row.length());
        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(row.charAt(start - 1))) {
            start--;
        }
        int end = caret;
        while (end < row.length() && Character.isJavaIdentifierPart(row.charAt(end))) {
            end++;
        }
        return row.substring(start, end);
    }
}
