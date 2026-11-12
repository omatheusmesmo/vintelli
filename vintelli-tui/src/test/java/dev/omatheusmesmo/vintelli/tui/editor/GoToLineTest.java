package dev.omatheusmesmo.vintelli.tui.editor;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoToLineTest {

    @Test
    void aBareLineGoesToItsFirstColumn() {
        assertEquals(Optional.of(new GoToLine(42, 1)), GoToLine.parse("42"));
    }

    @Test
    void aLineAndColumnKeepBothOneBasedNumbers() {
        assertEquals(Optional.of(new GoToLine(3, 7)), GoToLine.parse("3:7"));
    }

    @Test
    void surroundingSpacesAndLeadingZerosAreIgnored() {
        assertEquals(Optional.of(new GoToLine(12, 4)), GoToLine.parse("  012:004 "));
    }

    @Test
    void aHugeNumberSaturatesInsteadOfFailingSoTheEditorCanClampIt() {
        assertEquals(Optional.of(new GoToLine(Integer.MAX_VALUE, 1)), GoToLine.parse("99999999999999999999"));
    }

    @Test
    void emptyZeroNegativeAndMalformedInputIsRefused() {
        List<String> refused = List.of("", "   ", "0", "0:5", "5:0", "-3", "abc", "4x", "1.5", "1:2:3", ":4", "4:", "+4", "1 2");
        for (String input : refused) {
            assertTrue(GoToLine.parse(input).isEmpty(), "'" + input + "' must not parse");
        }
    }

    @Test
    void nullInputIsRefusedWithoutAFailure() {
        assertTrue(GoToLine.parse(null).isEmpty());
    }
}
