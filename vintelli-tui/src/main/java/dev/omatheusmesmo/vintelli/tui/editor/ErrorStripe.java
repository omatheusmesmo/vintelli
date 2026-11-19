package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;

import java.util.List;
import java.util.Optional;

/**
 * Pure arithmetic of the editor marker bar (the error stripe): which marker a bar cell shows, where a click
 * on a cell lands, which cells the viewport covers, and the problem summary of the editor header. The
 * rendering and the mouse routing in {@link EditorElement} only place what is computed here.
 */
final class ErrorStripe {

    /** The columns the editor needs before the bar takes one of them. */
    static final int MIN_EDITOR_WIDTH = 40;

    /** Marker kinds in priority order: a cell shows the lowest ordinal among the markers in its rows. */
    enum Kind {
        ERROR, WARNING, SEARCH, BREAKPOINT, CHANGE
    }

    /** A marker on a document row; {@code column} is where a click puts the caret. */
    record Mark(Kind kind, int row, int column) {
    }

    private ErrorStripe() {
    }

    /** The document rows each of {@code cellCount} cells covers: {@code ceil(lineCount / cellCount)}, at least one. */
    static int rowsPerCell(int lineCount, int cellCount) {
        if (cellCount <= 0) {
            return 1;
        }
        return Math.max(1, Math.ceilDiv(lineCount, cellCount));
    }

    /** The cell that covers a document row. */
    static int cellOf(int row, int lineCount, int cellCount) {
        return Math.clamp(row / rowsPerCell(lineCount, cellCount), 0, Math.max(0, cellCount - 1));
    }

    /**
     * The marker each cell shows: the most severe one among the rows it covers, and for equal severity the first
     * row. A cell without markers is null.
     */
    static Mark[] bucket(int lineCount, int cellCount, List<Mark> marks) {
        Mark[] cells = new Mark[Math.max(0, cellCount)];
        for (Mark mark : marks) {
            if (mark.row() < 0 || mark.row() >= lineCount || cells.length == 0) {
                continue;
            }
            int cell = cellOf(mark.row(), lineCount, cells.length);
            Mark shown = cells[cell];
            if (shown == null || beats(mark, shown)) {
                cells[cell] = mark;
            }
        }
        return cells;
    }

    private static boolean beats(Mark candidate, Mark shown) {
        int byKind = Integer.compare(candidate.kind().ordinal(), shown.kind().ordinal());
        return byKind < 0 || byKind == 0 && candidate.row() < shown.row();
    }

    /** The first and last cell, inclusive, that the document rows {@code firstRow..lastRow} cover. */
    static int[] thumb(int firstRow, int lastRow, int lineCount, int cellCount) {
        return new int[] {cellOf(firstRow, lineCount, cellCount), cellOf(Math.max(firstRow, lastRow), lineCount, cellCount)};
    }

    /** The scroll row that puts {@code row} in the middle of a viewport, clamped to the text. */
    static int topRowCentering(int row, int visibleRows, int lineCount) {
        return Math.clamp(row - visibleRows / 2, 0, Math.max(0, lineCount - visibleRows));
    }

    /** The document row a pointer on a cell stands for: the middle of the rows that cell covers. */
    static int rowAtCell(int cell, int lineCount, int cellCount) {
        int perCell = rowsPerCell(lineCount, cellCount);
        return Math.clamp(cell * perCell + perCell / 2, 0, Math.max(0, lineCount - 1));
    }

    /**
     * The problem summary of the editor header: the error and warning counts, or a check when the file was
     * analysed and is clean. Empty when no analysis reported on the file, since a check would claim a result
     * nobody computed.
     */
    static Optional<String> summary(List<Diagnostic> diagnostics, boolean analysed, boolean ascii) {
        long errors = diagnostics.stream().filter(value -> value.severity() == Severity.ERROR).count();
        long warnings = diagnostics.stream().filter(value -> value.severity() == Severity.WARNING).count();
        if (errors == 0 && warnings == 0) {
            return analysed ? Optional.of(ascii ? "ok" : "✓") : Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        if (errors > 0) {
            text.append(ascii ? "x " : "✖ ").append(errors);
        }
        if (warnings > 0) {
            text.append(text.isEmpty() ? "" : " ").append(ascii ? "! " : "▲ ").append(warnings);
        }
        return Optional.of(text.toString());
    }
}
