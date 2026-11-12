package dev.omatheusmesmo.vintelli.tui.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineShiftTest {
    @Test
    void unchangedTextKeepsEveryLine() {
        LineShift shift = LineShift.between("alpha\nbeta\ngamma", "alpha\nbeta\ngamma");

        assertEquals(0, shift.map(0));
        assertEquals(2, shift.map(2));
    }

    @Test
    void splittingALineKeepsTheAnchorOnItsFirstPartAndMovesTheLinesBelow() {
        LineShift shift = LineShift.between("one\nabcdef\nthree", "one\nabc\ndef\nthree");

        assertEquals(1, shift.map(1));
        assertEquals(3, shift.map(2));
    }

    @Test
    void newlineAtTheEndOfALineKeepsItsAnchor() {
        LineShift shift = LineShift.between("one\ntwo", "one\n\ntwo");

        assertEquals(0, shift.map(0));
        assertEquals(2, shift.map(1));
    }

    @Test
    void newlineAtTheStartOfALineMovesItsAnchorWithTheText() {
        LineShift shift = LineShift.between("one\ntwo", "one\n\ntwo");

        assertEquals(2, shift.map(1));
    }

    @Test
    void pastedLinesAboveAnAnchorShiftIt() {
        LineShift shift = LineShift.between("a\nb\nc", "x\ny\na\nb\nc");

        assertEquals(2, shift.map(0));
        assertEquals(4, shift.map(2));
    }

    @Test
    void removingWholeLinesRemovesTheirAnchorsAndShiftsTheRest() {
        LineShift shift = LineShift.between("a\nb\nc\nd", "a\nd");

        assertEquals(-1, shift.map(1));
        assertEquals(-1, shift.map(2));
        assertEquals(1, shift.map(3));
    }

    @Test
    void joiningALineIntoThePreviousOneKeepsTheFirstAnchorOnly() {
        LineShift shift = LineShift.between("a\nb\nc", "ab\nc");

        assertEquals(0, shift.map(0));
        assertEquals(-1, shift.map(1));
        assertEquals(1, shift.map(2));
    }

    @Test
    void duplicatingALineKeepsTheOriginalAnchorAndMovesTheLinesBelow() {
        LineShift shift = LineShift.between("a\nb\nc", "a\nb\nb\nc");

        assertEquals(1, shift.map(1));
        assertEquals(3, shift.map(2));
    }

    @Test
    void replacingARangeWithOneLineKeepsOnlyTheFirstAnchorInsideIt() {
        LineShift shift = LineShift.between("a\nb\nc\nd\ne", "a\nX\ne");

        assertEquals(1, shift.map(1));
        assertEquals(-1, shift.map(2));
        assertEquals(-1, shift.map(3));
        assertEquals(2, shift.map(4));
    }
}
