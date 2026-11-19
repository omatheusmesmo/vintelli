package dev.omatheusmesmo.vintelli.tui.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * One open diff tab: the file, its {@link DiffModel} and the cursor of the view. The layout is side by
 * side from {@link #UNIFIED_BELOW_COLUMNS} columns on and unified below until {@link #toggleUnified()}
 * picks the other layout, which then holds at any width. The cursor is a row of the side by side layout or a line of the unified one, and it is
 * carried over to the matching position when the layout changes.
 */
final class DiffTab {

    static final int UNIFIED_BELOW_COLUMNS = 100;

    private enum Choice { BY_WIDTH, SIDE_BY_SIDE, UNIFIED }

    private final Path file;
    private final DiffModel model;
    private Choice choice = Choice.BY_WIDTH;
    private boolean unified;
    private int width = UNIFIED_BELOW_COLUMNS;
    private int height = 1;
    private int cursor;
    private int scroll;

    DiffTab(Path file, DiffModel model) {
        this.file = file;
        this.model = model;
        this.cursor = model.hunks().isEmpty() ? 0 : model.hunks().getFirst().firstRow();
        this.scroll = Math.max(0, cursor - DiffModel.CONTEXT_LINES);
    }

    Path file() {
        return file;
    }

    DiffModel model() {
        return model;
    }

    String title(boolean ascii) {
        return file.getFileName() + (ascii ? " (HEAD <-> Working)" : " (HEAD ↔ Working)");
    }

    boolean unified() {
        return unified;
    }

    int cursor() {
        return cursor;
    }

    int scroll() {
        return scroll;
    }

    /** Fits the view to the body it is drawn in, switching the layout at the column threshold. */
    void layout(int columns, int rows) {
        width = columns;
        height = Math.max(1, rows);
        applyMode();
        reveal();
    }

    void toggleUnified() {
        choice = unified ? Choice.SIDE_BY_SIDE : Choice.UNIFIED;
        applyMode();
        reveal();
    }

    void move(int delta) {
        cursor = Math.clamp((long) cursor + delta, 0, Math.max(0, lineCount() - 1));
        reveal();
    }

    void page(int direction) {
        move(direction * Math.max(1, height - 1));
    }

    void scrollBy(int delta) {
        scroll = Math.clamp((long) scroll + delta, 0, Math.max(0, lineCount() - height));
    }

    /** Moves the cursor to the start of the next change below it. @return false when none is left */
    boolean nextHunk() {
        for (int start : hunkStarts()) {
            if (start > cursor) {
                jumpTo(start);
                return true;
            }
        }
        return false;
    }

    /** Moves the cursor to the start of the previous change above it. @return false when none is left */
    boolean previousHunk() {
        List<Integer> starts = hunkStarts();
        for (int index = starts.size() - 1; index >= 0; index--) {
            if (starts.get(index) < cursor) {
                jumpTo(starts.get(index));
                return true;
            }
        }
        return false;
    }

    /** The one based number of the change the cursor is in or below, 0 above the first one. */
    int currentHunk() {
        int current = 0;
        List<Integer> starts = hunkStarts();
        for (int index = 0; index < starts.size(); index++) {
            if (starts.get(index) <= cursor) {
                current = index + 1;
            }
        }
        return current;
    }

    /** The one based working line under the cursor, empty for a deleted file. */
    OptionalInt workingLine() {
        if (!model.workingPresent()) {
            return OptionalInt.empty();
        }
        boolean unifiedLine = unified && cursor < model.unified().size();
        return model.workingLineOf(unifiedLine ? model.unified().get(cursor).row() : unified ? 0 : cursor);
    }

    int lineCount() {
        return unified ? model.unified().size() : model.rows().size();
    }

    private List<Integer> hunkStarts() {
        List<Integer> starts = new ArrayList<>();
        for (int hunk = 0; hunk < model.hunks().size(); hunk++) {
            starts.add(unified ? model.unifiedStartOf(hunk) : model.hunks().get(hunk).firstRow());
        }
        return starts;
    }

    private void jumpTo(int line) {
        cursor = line;
        scroll = Math.clamp(cursor - height / 3, 0, Math.max(0, lineCount() - height));
    }

    private void applyMode() {
        boolean next = switch (choice) {
            case BY_WIDTH -> width < UNIFIED_BELOW_COLUMNS;
            case SIDE_BY_SIDE -> false;
            case UNIFIED -> true;
        };
        if (next == unified) {
            return;
        }
        if (next) {
            cursor = model.unified().isEmpty() ? 0 : model.unifiedIndexOf(cursor);
        } else if (!model.unified().isEmpty()) {
            cursor = model.unified().get(Math.min(cursor, model.unified().size() - 1)).row();
        }
        unified = next;
        scroll = Math.clamp(cursor - height / 3, 0, Math.max(0, lineCount() - height));
    }

    private void reveal() {
        if (cursor < scroll) {
            scroll = cursor;
        } else if (cursor >= scroll + height) {
            scroll = cursor - height + 1;
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, lineCount() - 1));
    }
}
