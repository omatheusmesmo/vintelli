package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.ArrayList;
import java.util.List;

import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Face;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Piece;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Row;

/**
 * The row builder both documentation readers write into. It keeps the styles open at the caret of the text (bold,
 * code, preformatted), collapses the white space of prose, keeps the lines of preformatted text, and separates the
 * blocks with one blank row. A list item's marker waits for the first text of the item, so an item and its first
 * paragraph share one row.
 */
final class HoverRows {

    private static final Row BLANK = new Row(List.of());

    private final List<Row> rows = new ArrayList<>();
    private final List<Piece> line = new ArrayList<>();
    private String marker = "";
    private int bold;
    private int code;
    private int preformatted;

    /**
     * Adds text in the open style. Prose collapses its white space; preformatted text keeps its line breaks.
     */
    void text(String raw) {
        if (preformatted > 0) {
            preformattedText(raw);
            return;
        }
        String collapsed = raw.replaceAll("\\s+", " ");
        if (line.isEmpty() || line.getLast().text().endsWith(" ")) {
            collapsed = collapsed.startsWith(" ") ? collapsed.substring(1) : collapsed;
        }
        piece(collapsed);
    }

    void lineBreak() {
        flush();
    }

    void block() {
        if (!marker.isEmpty()) {
            return;
        }
        flush();
        if (!rows.isEmpty() && !rows.getLast().pieces().isEmpty()) {
            rows.add(BLANK);
        }
    }

    void endBlock() {
        flush();
    }

    void item(String itemMarker) {
        flush();
        marker = itemMarker;
    }

    void enter(Face face) {
        adjust(face, 1);
    }

    void leave(Face face) {
        adjust(face, -1);
    }

    void enterPreformatted() {
        preformatted++;
    }

    void leavePreformatted() {
        preformatted = Math.max(0, preformatted - 1);
    }

    List<Row> finish() {
        flush();
        int first = 0;
        int last = rows.size();
        while (first < last && rows.get(first).pieces().isEmpty()) {
            first++;
        }
        while (last > first && rows.get(last - 1).pieces().isEmpty()) {
            last--;
        }
        return List.copyOf(rows.subList(first, last));
    }

    private void preformattedText(String raw) {
        String[] lines = raw.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                flush();
                if (lines[index].isEmpty()) {
                    rows.add(BLANK);
                }
            }
            piece(lines[index]);
        }
    }

    private void piece(String text) {
        if (text.isEmpty()) {
            return;
        }
        if (line.isEmpty() && !marker.isEmpty()) {
            line.add(new Piece(marker, Face.PLAIN));
            marker = "";
        }
        line.add(new Piece(text, currentFace()));
    }

    private void flush() {
        if (line.isEmpty()) {
            if (!marker.isEmpty()) {
                rows.add(new Row(List.of(new Piece(marker.strip(), Face.PLAIN))));
                marker = "";
            }
            return;
        }
        trimTrailingSpace();
        rows.add(new Row(line));
        line.clear();
    }

    private void trimTrailingSpace() {
        while (!line.isEmpty()) {
            Piece last = line.getLast();
            String stripped = last.text().stripTrailing();
            if (!stripped.isEmpty()) {
                line.set(line.size() - 1, new Piece(stripped, last.face()));
                return;
            }
            line.removeLast();
        }
    }

    private void adjust(Face face, int delta) {
        if (face == Face.BOLD) {
            bold = Math.max(0, bold + delta);
        } else if (face == Face.CODE) {
            code = Math.max(0, code + delta);
        }
    }

    private Face currentFace() {
        if (code > 0) {
            return Face.CODE;
        }
        return bold > 0 ? Face.BOLD : Face.PLAIN;
    }
}
