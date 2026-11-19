package dev.omatheusmesmo.vintelli.tui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.tamboui.style.Color;
import org.junit.jupiter.api.Test;

class SgrCompatibilityTest {

    private static final Pattern SEQUENCE = Pattern.compile("\u001b\\[([0-9;:]*)m");

    @Test
    void truecolorUnderlineBecomesTheColonForm() {
        assertEquals("\u001b[4;58:2::1:2:3m", SgrCompatibility.forTerminals("\u001b[4;58;2;1;2;3m"));
    }

    @Test
    void indexedUnderlineBecomesTheColonForm() {
        assertEquals("\u001b[58:5:196m", SgrCompatibility.forTerminals("\u001b[58;5;196m"));
    }

    @Test
    void theFormTamboUiEmitsIsRewrittenToTheColonForm() {
        String emitted = Color.rgb(223, 225, 229).toAnsiUnderline();
        assertEquals("\u001b[58:2::223:225:229m", SgrCompatibility.forTerminals("\u001b[" + emitted + "m"));
    }

    @Test
    void severalSequencesInOneStringAreEachRewritten() {
        assertEquals("a\u001b[58:2::1:2:3mb\u001b[58:5:9mc",
                SgrCompatibility.forTerminals("a\u001b[58;2;1;2;3mb\u001b[58;5;9mc"));
    }

    @Test
    void textOutsideEscapeSequencesIsNeverRewritten() {
        String text = "58;2;1;2;3 and 58;5;9 printed as text";
        assertSame(text, SgrCompatibility.forTerminals(text));
    }

    @Test
    void aUnderlineComponentInsideAForegroundColorIsLeftAlone() {
        assertEquals("\u001b[38;2;58;2;1m", SgrCompatibility.forTerminals("\u001b[38;2;58;2;1m"));
        assertEquals("\u001b[38;5;58;2;1;2;3m", SgrCompatibility.forTerminals("\u001b[38;5;58;2;1;2;3m"));
    }

    @Test
    void aShortSequenceWithoutAnUnderlineColorIsReturnedUnchanged() {
        String output = "\u001b[0;1;31mhello\u001b[0m";
        assertSame(output, SgrCompatibility.forTerminals(output));
    }

    @Test
    void theSeventeenParameterSequenceFitsInOneSequenceAfterTheColonForm() {
        String output = "\u001b[0;38;2;223;225;229;48;2;30;31;34;4;58;2;43;45;48m";
        String rewritten = SgrCompatibility.forTerminals(output);

        assertEquals("\u001b[0;38;2;223;225;229;48;2;30;31;34;4;58:2::43:45:48m", rewritten);
        assertParameterCountsWithin(rewritten, 16);
    }

    @Test
    void aSequenceAboveSixteenParametersIsSplitBetweenAttributes() {
        String output = "\u001b[1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16;17m";
        String rewritten = SgrCompatibility.forTerminals(output);

        assertEquals("\u001b[1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16m\u001b[17m", rewritten);
    }

    @Test
    void fullStyleSplitsOnlyAtAttributeAndColorBoundaries() {
        String output = "\u001b[0;38;2;10;20;30;48;2;40;50;60;1;2;3;4;9;58;2;70;80;90m";

        String rewritten = SgrCompatibility.forTerminals(output);

        assertEquals(List.of(
                List.of("0", "38", "2", "10", "20", "30", "48", "2", "40", "50", "60", "1", "2", "3", "4", "9"),
                List.of("58:2::70:80:90")), sequenceParameters(rewritten));
        assertParameterCountsWithin(rewritten, 16);
    }

    @Test
    void onlyTheFirstSplitSequenceKeepsTheLeadingReset() {
        String output = "\u001b[0;38;2;10;20;30;48;2;40;50;60;1;2;3;4;9;5;6;7;8m";

        List<List<String>> sequences = sequenceParameters(SgrCompatibility.forTerminals(output));

        assertEquals(2, sequences.size());
        assertEquals("0", sequences.getFirst().getFirst());
        assertNotEquals("0", sequences.getLast().getFirst());
        assertEquals(List.of("5", "6", "7", "8"), sequences.getLast());
        assertParameterCountsWithin(SgrCompatibility.forTerminals(output), 16);
    }

    private static void assertParameterCountsWithin(String output, int limit) {
        for (List<String> parameters : sequenceParameters(output)) {
            assertTrue(parameters.size() <= limit, () -> "more than " + limit + " parameters: " + parameters);
        }
    }

    private static List<List<String>> sequenceParameters(String output) {
        List<List<String>> sequences = new ArrayList<>();
        Matcher matcher = SEQUENCE.matcher(output);
        while (matcher.find()) {
            sequences.add(List.of(matcher.group(1).split(";", -1)));
        }
        return sequences;
    }
}
