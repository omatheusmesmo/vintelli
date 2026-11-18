package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Face;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Piece;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Row;
import dev.tamboui.text.CharWidth;

class HoverTextTest {

    private static final String JDTLS_SIGNATURE = """
            ```java
            int String.length()
            ```
            ---
            Returns the length of this string.
            """;

    @Test
    void theSignatureComesFirstInTheCodeFaceAndTheDocumentationFollowsIt() {
        List<Row> rows = HoverText.parse(JDTLS_SIGNATURE);

        assertEquals(List.of("int String.length()", "", "Returns the length of this string."), plain(rows));
        assertEquals(Face.CODE, rows.get(0).pieces().get(0).face());
        assertEquals(Face.PLAIN, rows.get(2).pieces().get(0).face());
    }

    @Test
    void markdownMarkersLeaveNoTraceAndKeepTheirStyles() {
        List<Row> rows = HoverText.parse("# Title\n\nSome **bold** and `code` and [a link](https://example.org) text.");

        assertEquals(List.of("Title", "", "Some bold and code and a link text."), plain(rows));
        assertEquals(List.of(new Piece("Title", Face.BOLD)), rows.get(0).pieces());
        assertEquals(new Piece("bold", Face.BOLD), rows.get(2).pieces().get(1));
        assertEquals(new Piece("code", Face.CODE), rows.get(2).pieces().get(3));
    }

    @Test
    void javadocHtmlBecomesTextWithItsParagraphsPreformattedLinesAndListItems() {
        List<Row> rows = HoverText.parse("""
                <p>Returns the <code>length</code> of this string.</p>
                <pre>int x = 1;
                  int y;</pre>
                <ul><li>first</li><li>second</li></ul>
                """);

        assertEquals(List.of("Returns the length of this string.", "", "int x = 1;", "  int y;", "", "- first",
                "- second"), plain(rows));
        assertEquals(new Piece("length", Face.CODE), rows.get(0).pieces().get(1));
    }

    @Test
    void htmlEntitiesAreDecodedAndAnEscapedTagStaysText() {
        List<Row> rows = HoverText.parse("<p>Map&lt;K, V&gt; &amp; &#x41;&#39;s &quot;copy&quot;</p>");

        assertEquals(List.of("Map<K, V> & A's \"copy\""), plain(rows));
    }

    @Test
    void theRealJdtlsSampleLeavesNoMarkupOnScreen() {
        List<String> screen = plain(HoverText.parse(JDTLS_SIGNATURE + """

                Returns the <code>length</code> of this string.<br>
                <p>See <b>Unicode</b> and more.</p>
                """));

        assertTrue(screen.contains("Returns the length of this string."), screen.toString());
        assertTrue(screen.contains("See Unicode and more."), screen.toString());
        for (String line : screen) {
            assertFalse(line.contains("<") || line.contains(">") || line.contains("`") || line.contains("*"),
                    "markup left on screen: " + line);
        }
    }

    @Test
    void anEmptyOrBlankDocumentationHasNoRows() {
        assertEquals(List.of(), HoverText.parse(""));
        assertEquals(List.of(), HoverText.parse("   \n\n  "));
        assertEquals(List.of(), HoverText.parse(null));
    }

    @Test
    void wideCharactersCountForTwoCellsWhenWrapping() {
        List<Row> wrapped = HoverText.wrap(List.of(row("数据数据数据")), 5);

        assertEquals(List.of("数据", "数据", "数据"), plain(wrapped));
        for (Row row : wrapped) {
            assertTrue(CharWidth.of(text(row)) <= 5, text(row));
        }
        assertEquals(1, HoverText.wrap(List.of(row("abcde")), 5).size(), "five narrow cells fit a width of five");
    }

    @Test
    void wordsBreakAtSpacesAndAWordWiderThanThePanelIsCut() {
        assertEquals(List.of("alpha beta", "gamma"), plain(HoverText.wrap(List.of(row("alpha beta gamma")), 10)));
        assertEquals(List.of("abcdef", "ghijkl", "mno"), plain(HoverText.wrap(List.of(row("abcdefghijklmno")), 6)));
    }

    @Test
    void aBlankRowStaysOneRowAndCodeKeepsItsIndentation() {
        List<Row> rows = HoverText.parse("```\n  x\n\n  y\n```");

        assertEquals(List.of("  x", "", "  y"), plain(rows));
    }

    private static Row row(String text) {
        return new Row(List.of(new Piece(text, Face.PLAIN)));
    }

    private static String text(Row row) {
        return row.pieces().stream().map(Piece::text).reduce("", String::concat);
    }

    private static List<String> plain(List<Row> rows) {
        return rows.stream().map(HoverTextTest::text).toList();
    }
}
