package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the bracket that pairs with the one next to the caret. A bracket
 * counts when it sits right after the caret, which is checked first, or right
 * before it. Its partner is the nearest bracket of the same kind that balances
 * it: the search runs forward from an opener and backward from a closer, and
 * only the brackets that {@link CodeCheck} accepts take part, so brackets in
 * strings, chars, text blocks and comments never pair. The search stops after
 * {@link #MAX_LINES} lines in each direction.
 */
final class BraceMatcher {

    static final int MAX_LINES = 5000;

    private static final String OPENERS = "([{";
    private static final String CLOSERS = ")]}";

    private BraceMatcher() {
    }

    /** Tells whether the character at a row and column is code rather than string, char, text block or comment. */
    @FunctionalInterface
    interface CodeCheck {
        boolean isCode(int row, int column);
    }

    /**
     * Two paired brackets, each as a row and a character column. The first one is the bracket next to the caret.
     *
     * @param row the row of the bracket next to the caret
     * @param column the column of the bracket next to the caret
     * @param matchRow the row of its partner
     * @param matchColumn the column of its partner
     */
    record BracePair(int row, int column, int matchRow, int matchColumn) {

        List<Integer> columnsOn(int line) {
            List<Integer> columns = new ArrayList<>(2);
            if (line == row) {
                columns.add(column);
            }
            if (line == matchRow) {
                columns.add(matchColumn);
            }
            return columns;
        }
    }

    /**
     * Finds the partner of the bracket next to the caret.
     *
     * @param lines the text, one string per row
     * @param code tells whether a character is code
     * @param row the caret row
     * @param column the caret character column
     * @return the pair, or empty when no bracket touches the caret or its partner is not found
     */
    static Optional<BracePair> find(List<String> lines, CodeCheck code, int row, int column) {
        if (row < 0 || row >= lines.size()) {
            return Optional.empty();
        }
        int anchor;
        if (isBracketAt(lines, code, row, column - 1)) {
            anchor = column - 1;
        } else if (isBracketAt(lines, code, row, column)) {
            anchor = column;
        } else {
            return Optional.empty();
        }
        int step = OPENERS.indexOf(lines.get(row).charAt(anchor)) >= 0 ? 1 : -1;
        return scan(lines, code, row, anchor, step);
    }

    private static Optional<BracePair> scan(List<String> lines, CodeCheck code, int row, int anchor, int step) {
        char same = lines.get(row).charAt(anchor);
        char partner = partnerOf(same);
        int last = step > 0 ? Math.min(lines.size() - 1, row + MAX_LINES) : Math.max(0, row - MAX_LINES);
        int depth = 0;
        for (int line = row; step > 0 ? line <= last : line >= last; line += step) {
            String text = lines.get(line);
            int column = line == row ? anchor + step : step > 0 ? 0 : text.length() - 1;
            for (; column >= 0 && column < text.length(); column += step) {
                char c = text.charAt(column);
                if ((c != same && c != partner) || !code.isCode(line, column)) {
                    continue;
                }
                if (c == same) {
                    depth++;
                } else if (depth == 0) {
                    return Optional.of(new BracePair(row, anchor, line, column));
                } else {
                    depth--;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isBracketAt(List<String> lines, CodeCheck code, int row, int column) {
        String text = lines.get(row);
        return column >= 0 && column < text.length() && isBracket(text.charAt(column))
                && code.isCode(row, column);
    }

    private static boolean isBracket(char c) {
        return OPENERS.indexOf(c) >= 0 || CLOSERS.indexOf(c) >= 0;
    }

    private static char partnerOf(char bracket) {
        int opener = OPENERS.indexOf(bracket);
        return opener >= 0 ? CLOSERS.charAt(opener) : OPENERS.charAt(CLOSERS.indexOf(bracket));
    }
}
