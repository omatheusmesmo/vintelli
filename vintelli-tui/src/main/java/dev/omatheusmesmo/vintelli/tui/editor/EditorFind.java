package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find state of the built-in editor: a literal, case-sensitive query over one document, its matches in
 * document rows and columns, and the current match. Searching only moves the caret. It never edits the
 * text, so the document stays clean and its undo history stays untouched. Guarded by BuiltinEditor.
 */
final class EditorFind {
    private static final int NONE = -1;

    record Match(int row, int column, int length) {
    }

    private final StringBuilder query = new StringBuilder();
    private EditorDocument owner;
    private List<Match> matches = List.of();
    private String indexedText;
    private int current = NONE;
    private int anchorRow;
    private int anchorColumn;

    boolean isOpen() {
        return owner != null;
    }

    boolean isOpenOn(EditorDocument document) {
        return owner != null && owner == document;
    }

    void open(EditorDocument document) {
        owner = document;
        anchorRow = document.row();
        anchorColumn = document.column();
        indexedText = null;
        sync(true);
    }

    void close() {
        owner = null;
    }

    void syncTo(EditorDocument document) {
        if (owner != null && owner != document) {
            owner = null;
        }
    }

    String query() {
        return query.toString();
    }

    void type(int codePoint) {
        query.appendCodePoint(codePoint);
        sync(true);
        revealCurrent();
    }

    void backspace() {
        if (query.isEmpty()) {
            return;
        }
        query.setLength(query.offsetByCodePoints(query.length(), -1));
        sync(true);
        revealCurrent();
    }

    void next() {
        List<Match> found = matches();
        if (found.isEmpty()) {
            return;
        }
        current = (current + 1) % found.size();
        moveTo(found.get(current));
    }

    void previous() {
        List<Match> found = matches();
        if (found.isEmpty()) {
            return;
        }
        current = Math.floorMod(current - 1, found.size());
        moveTo(found.get(current));
    }

    List<Match> matches() {
        sync(false);
        return matches;
    }

    int currentIndex() {
        matches();
        return current;
    }

    String describe() {
        List<Match> found = matches();
        if (query.isEmpty()) {
            return "Type to search";
        }
        if (found.isEmpty()) {
            return "No results";
        }
        return (current + 1) + " of " + found.size();
    }

    private void revealCurrent() {
        if (current != NONE) {
            owner.position(matches.get(current).row(), matches.get(current).column());
        }
    }

    private void moveTo(Match match) {
        anchorRow = match.row();
        anchorColumn = match.column();
        owner.position(match.row(), match.column());
    }

    private void sync(boolean reanchor) {
        String text = owner.text();
        if (!reanchor && text.equals(indexedText)) {
            return;
        }
        indexedText = text;
        matches = locate(text, query.toString());
        if (matches.isEmpty()) {
            current = NONE;
        } else if (reanchor || current < 0 || current >= matches.size()) {
            current = firstAtOrAfterAnchor();
        }
    }

    private int firstAtOrAfterAnchor() {
        for (int index = 0; index < matches.size(); index++) {
            Match found = matches.get(index);
            if (found.row() > anchorRow || found.row() == anchorRow && found.column() >= anchorColumn) {
                return index;
            }
        }
        return 0;
    }

    static List<Match> locate(String text, String query) {
        if (query.isEmpty()) {
            return List.of();
        }
        List<Match> found = new ArrayList<>();
        Matcher matcher = Pattern.compile(Pattern.quote(query)).matcher(text);
        int row = 0;
        int lineStart = 0;
        int newline = text.indexOf('\n');
        while (matcher.find()) {
            int offset = matcher.start();
            while (newline >= 0 && newline < offset) {
                row++;
                lineStart = newline + 1;
                newline = text.indexOf('\n', lineStart);
            }
            found.add(new Match(row, offset - lineStart, query.length()));
        }
        return List.copyOf(found);
    }
}
