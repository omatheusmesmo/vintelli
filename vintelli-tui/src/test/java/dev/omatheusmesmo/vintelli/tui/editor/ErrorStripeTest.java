package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.tui.editor.ErrorStripe.Kind;
import dev.omatheusmesmo.vintelli.tui.editor.ErrorStripe.Mark;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ErrorStripeTest {

    @Test
    void aCellCoversTheCeilingOfLinesOverCells() {
        assertEquals(48, ErrorStripe.rowsPerCell(1000, 21));
        assertEquals(1, ErrorStripe.rowsPerCell(5, 20));
        assertEquals(1, ErrorStripe.rowsPerCell(20, 20));
        assertEquals(2, ErrorStripe.rowsPerCell(21, 20));
    }

    @Test
    void aRowLandsInTheCellThatCoversIt() {
        assertEquals(18, ErrorStripe.cellOf(899, 1000, 21));
        assertEquals(0, ErrorStripe.cellOf(47, 1000, 21));
        assertEquals(1, ErrorStripe.cellOf(48, 1000, 21));
        assertEquals(20, ErrorStripe.cellOf(999, 1000, 21));
    }

    @Test
    void theMostSevereMarkerOfTheRangeWinsInPriorityOrder() {
        List<Mark> marks = List.of(
                new Mark(Kind.CHANGE, 1, 0),
                new Mark(Kind.BREAKPOINT, 2, 0),
                new Mark(Kind.SEARCH, 3, 0),
                new Mark(Kind.WARNING, 4, 0),
                new Mark(Kind.ERROR, 5, 0),
                new Mark(Kind.CHANGE, 105, 0),
                new Mark(Kind.BREAKPOINT, 106, 0),
                new Mark(Kind.SEARCH, 210, 0),
                new Mark(Kind.WARNING, 310, 0),
                new Mark(Kind.WARNING, 311, 0));

        Mark[] cells = ErrorStripe.bucket(400, 4, marks);

        assertEquals(new Mark(Kind.ERROR, 5, 0), cells[0]);
        assertEquals(new Mark(Kind.BREAKPOINT, 106, 0), cells[1]);
        assertEquals(new Mark(Kind.SEARCH, 210, 0), cells[2]);
        assertEquals(new Mark(Kind.WARNING, 310, 0), cells[3], "equal severity keeps the first row of the range");
    }

    @Test
    void aCellWithoutMarkersAndMarkersOutsideTheTextShowNothing() {
        Mark[] cells = ErrorStripe.bucket(10, 10, List.of(new Mark(Kind.ERROR, 3, 0), new Mark(Kind.ERROR, 10, 0),
                new Mark(Kind.ERROR, -1, 0)));

        assertNull(cells[0]);
        assertEquals(Kind.ERROR, cells[3].kind());
        assertEquals(10, cells.length);
        assertNull(cells[9], "a marker below the last line is dropped");
    }

    @Test
    void theThumbCoversTheCellsOfTheVisibleRows() {
        assertArrayEquals(new int[] {0, 0}, ErrorStripe.thumb(0, 20, 1000, 21));
        assertArrayEquals(new int[] {10, 10}, ErrorStripe.thumb(480, 500, 1000, 21));
        assertArrayEquals(new int[] {20, 20}, ErrorStripe.thumb(960, 999, 1000, 21));
    }

    @Test
    void centeringClampsAtBothEndsOfTheText() {
        assertEquals(0, ErrorStripe.topRowCentering(3, 20, 1000));
        assertEquals(890, ErrorStripe.topRowCentering(900, 20, 1000));
        assertEquals(980, ErrorStripe.topRowCentering(999, 20, 1000));
        assertEquals(0, ErrorStripe.topRowCentering(5, 20, 10));
    }

    @Test
    void summaryCountsErrorsAndWarningsAndShowsACheckOnlyForAnAnalysedCleanFile() {
        Diagnostic error = diagnostic(Severity.ERROR);
        Diagnostic warning = diagnostic(Severity.WARNING);
        Diagnostic hint = diagnostic(Severity.HINT);

        assertEquals(Optional.of("✖ 2 ▲ 1"), ErrorStripe.summary(List.of(error, error, warning, hint), true, false));
        assertEquals(Optional.of("▲ 1"), ErrorStripe.summary(List.of(warning), true, false));
        assertEquals(Optional.of("x 1 ! 1"), ErrorStripe.summary(List.of(error, warning), true, true));
        assertEquals(Optional.of("✓"), ErrorStripe.summary(List.of(hint), true, false));
        assertEquals(Optional.empty(), ErrorStripe.summary(List.of(), false, false),
                "a file nobody analysed gets no check");
    }

    private static Diagnostic diagnostic(Severity severity) {
        return new Diagnostic(Path.of("A.java"), 0, 0, 0, 1, severity, "test", "problem");
    }
}
