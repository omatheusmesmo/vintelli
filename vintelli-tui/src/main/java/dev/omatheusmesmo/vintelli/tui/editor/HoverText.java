package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.text.CharWidth;

/**
 * Turns the documentation a language server sends, markdown or the HTML of Javadoc, into rows of styled text for
 * the quick documentation popup, and wraps the rows to the width of the popup. The markdown is read by
 * {@link HoverMarkdown} and the HTML by {@link HoverHtml}; both fill the same {@link HoverRows}. Widths are display
 * cells, so a wide character counts for two.
 */
final class HoverText {

    enum Face {
        PLAIN, BOLD, CODE
    }

    record Piece(String text, Face face) {
    }

    record Row(List<Piece> pieces) {

        Row {
            pieces = List.copyOf(pieces);
        }

        int width() {
            return pieces.stream().mapToInt(piece -> CharWidth.of(piece.text())).sum();
        }
    }

    private HoverText() {
    }

    /**
     * @return the rows of the documentation, the signature first when the server sends it first; empty for an
     *         empty documentation
     */
    static List<Row> parse(String documentation) {
        HoverRows rows = new HoverRows();
        if (documentation != null && !documentation.isBlank()) {
            HoverMarkdown.render(documentation, rows);
        }
        return rows.finish();
    }

    /**
     * Cuts every row into display rows no wider than {@code width} cells. Rows break at spaces, a word wider than
     * the width is cut inside, and the spaces that start a continuation row are dropped. An empty row stays one row.
     */
    static List<Row> wrap(List<Row> rows, int width) {
        int limit = Math.max(1, width);
        List<Row> wrapped = new ArrayList<>();
        for (Row row : rows) {
            if (row.pieces().isEmpty()) {
                wrapped.add(row);
            } else {
                new Wrapper(limit, wrapped).row(row);
            }
        }
        return List.copyOf(wrapped);
    }

    private static final class Wrapper {

        private final int limit;
        private final List<Row> out;
        private final List<Piece> line = new ArrayList<>();
        private int lineWidth;
        private boolean continuation;

        Wrapper(int limit, List<Row> out) {
            this.limit = limit;
            this.out = out;
        }

        void row(Row row) {
            for (Piece piece : row.pieces()) {
                for (Piece token : tokens(piece)) {
                    add(token);
                }
            }
            emit();
            continuation = false;
        }

        private void add(Piece token) {
            int width = CharWidth.of(token.text());
            if (token.text().isBlank()) {
                if (line.isEmpty() && continuation) {
                    return;
                }
                if (lineWidth + width > limit) {
                    breakLine();
                    return;
                }
                append(token.text(), token.face(), width);
                return;
            }
            if (lineWidth + width > limit && !line.isEmpty()) {
                breakLine();
            }
            if (width > limit) {
                cut(token);
            } else {
                append(token.text(), token.face(), width);
            }
        }

        private void cut(Piece token) {
            token.text().codePoints().forEach(codePoint -> {
                int width = CharWidth.of(codePoint);
                if (lineWidth + width > limit && !line.isEmpty()) {
                    breakLine();
                }
                append(new String(Character.toChars(codePoint)), token.face(), width);
            });
        }

        private void append(String text, Face face, int width) {
            int last = line.size() - 1;
            if (last >= 0 && line.get(last).face() == face) {
                line.set(last, new Piece(line.get(last).text() + text, face));
            } else {
                line.add(new Piece(text, face));
            }
            lineWidth += width;
        }

        private void breakLine() {
            emit();
            continuation = true;
        }

        private void emit() {
            while (!line.isEmpty()) {
                Piece last = line.getLast();
                String stripped = last.text().stripTrailing();
                if (!stripped.isEmpty()) {
                    line.set(line.size() - 1, new Piece(stripped, last.face()));
                    break;
                }
                line.removeLast();
            }
            if (!line.isEmpty()) {
                out.add(new Row(line));
                line.clear();
            }
            lineWidth = 0;
        }

        private static List<Piece> tokens(Piece piece) {
            List<Piece> tokens = new ArrayList<>();
            String text = piece.text();
            StringBuilder run = new StringBuilder();
            Boolean runIsSpace = null;
            for (int index = 0; index < text.length();) {
                int codePoint = text.codePointAt(index);
                boolean space = Character.isWhitespace(codePoint);
                if (runIsSpace != null && space != runIsSpace) {
                    tokens.add(new Piece(run.toString(), piece.face()));
                    run.setLength(0);
                }
                run.appendCodePoint(codePoint);
                runIsSpace = space;
                index += Character.charCount(codePoint);
            }
            if (!run.isEmpty()) {
                tokens.add(new Piece(run.toString(), piece.face()));
            }
            return tokens;
        }
    }
}
