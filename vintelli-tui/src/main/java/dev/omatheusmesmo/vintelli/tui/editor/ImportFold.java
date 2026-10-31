package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A collapsed Java import block: the document rows {@code [start, start + count)}
 * are shown as the single placeholder row {@code start}. Pure row arithmetic, so
 * the document stays the only owner of the text.
 *
 * @param start first document row of the import block
 * @param count number of document rows the block spans, blank separators included
 * @param statements number of {@code import} statements inside the block
 */
record ImportFold(int start, int count, int statements) {
    private static final Pattern IMPORT = Pattern.compile("import\\s+(static\\s+)?[\\w.*]+\\s*;.*");

    String placeholder() {
        return "import ... (" + statements + (statements == 1 ? " statement)" : " statements)");
    }

    boolean hides(int documentRow) {
        return documentRow >= start && documentRow < start + count;
    }

    int toView(int documentRow) {
        return documentRow < start ? documentRow : hides(documentRow) ? start : documentRow - count + 1;
    }

    int toDocument(int viewRow) {
        return viewRow <= start ? viewRow : viewRow + count - 1;
    }

    SelectionCursor toView(SelectionCursor selection) {
        return new SelectionCursor(toView(selection.anchorRow()), viewColumn(selection.anchorRow(), selection.anchorCol()),
                toView(selection.cursorRow()), viewColumn(selection.cursorRow(), selection.cursorCol()));
    }

    SelectionCursor toDocument(SelectionCursor selection) {
        return new SelectionCursor(toDocument(selection.anchorRow()), documentColumn(selection.anchorRow(), selection.anchorCol()),
                toDocument(selection.cursorRow()), documentColumn(selection.cursorRow(), selection.cursorCol()));
    }

    int viewColumn(int documentRow, int column) {
        return hides(documentRow) ? 0 : column;
    }

    int documentColumn(int viewRow, int column) {
        return viewRow == start ? 0 : column;
    }

    /**
     * Finds the leading import block: the span from the first to the last
     * {@code import} line, reached through the package line, comments and
     * blank lines only and ended by the first other code line.
     *
     * @param text the full document text
     * @return the block, or empty when the document has no import statements
     */
    static Optional<ImportFold> detect(String text) {
        String[] lines = text.split("\n", -1);
        int first = -1;
        int last = -1;
        int statements = 0;
        boolean inComment = false;
        for (int row = 0; row < lines.length; row++) {
            String line = lines[row].strip();
            if (IMPORT.matcher(line).matches()) {
                if (first < 0) { first = row; }
                last = row;
                statements++;
            } else if (inComment || line.startsWith("/*")) {
                inComment = !line.contains("*/") || line.lastIndexOf("/*") > line.lastIndexOf("*/");
            } else if (!line.isEmpty() && !line.startsWith("//") && !(first < 0 && line.startsWith("package "))) {
                break;
            }
        }
        return first < 0 ? Optional.empty() : Optional.of(new ImportFold(first, last - first + 1, statements));
    }
}
