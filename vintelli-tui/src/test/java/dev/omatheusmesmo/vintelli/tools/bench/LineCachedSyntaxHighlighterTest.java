package dev.omatheusmesmo.vintelli.tools.bench;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import dev.tamboui.style.AnsiColor;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.syntax.SyntaxTheme;
import dev.tamboui.widgets.syntax.TokenType;

class LineCachedSyntaxHighlighterTest {

    private static final String LANGUAGE = "java";

    private static final String CORPUS = String.join("\n", List.of(
        "final class Sample {",
        "    private final String plain = \"a /* not a comment */ b\";",
        "    /* block",
        "       comment */",
        "    private final char quote = '\"';",
        "    private final String escaped = \"esc \\\" quote\";",
        "    private final String block = \"\"\"",
        "        text // not a comment",
        "        \"\"\";",
        "    int count = 1;",
        "}"));

    private static final List<Edit> CORPUS_EDITS = List.of(
        new Edit("    /* block", "    block"),
        new Edit("    block", "    /* block"),
        new Edit("int count = 1;", "int count = 12;"),
        new Edit("        \"\"\";", "        ;"),
        new Edit("        ;", "        \"\"\";"),
        new Edit("'\"'", "'x'"),
        new Edit("esc \\\" quote", "esc quote"),
        new Edit("    int count = 12;", "    int count = 12;\n    int extra = 3;"),
        new Edit("\n    int extra = 3;", ""),
        new Edit("    int count = 12;", "    int count = 12; /* open"),
        new Edit(" /* open", ""));

    private static final Color ERROR_BACKGROUND = Color.ansi(AnsiColor.RED);

    @Test
    void warmIncrementalResultEqualsFreshFullResultAfterASeriesOfEdits() {
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        String text = CORPUS;
        assertWarmMatchesFresh(warm, text, "initial");
        for (Edit edit : CORPUS_EDITS) {
            Assertions.assertTrue(text.contains(edit.from()), "edit anchor missing: " + edit.from());
            text = text.replace(edit.from(), edit.to());
            assertWarmMatchesFresh(warm, text, edit.from() + " -> " + edit.to());
        }
    }

    @Test
    void editInsideOneLineRetokenizesOnlyThatLine() {
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(CORPUS, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        warm.highlight(CORPUS.replace("int count = 1;", "int count = 19;"), LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(1, warm.lastTokenizedLines());
    }

    @Test
    void endStateChangeRetokenizesOnlyUntilTheStateConverges() {
        String text = "a\n/* open\nb\nc */\nd\ne";
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);

        text = text.replace("/* open", "/* opened");
        warm.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(1, warm.lastTokenizedLines(), "in-line edit inside an open comment");

        text = text.replace("/* opened", "opened");
        assertWarmMatchesFresh(warm, text, "opener removed");
        Assertions.assertEquals(3, warm.lastTokenizedLines(), "edited row plus the rows whose state changed");

        text = text.replace("opened", "/* opened");
        assertWarmMatchesFresh(warm, text, "opener restored");
        Assertions.assertEquals(3, warm.lastTokenizedLines(), "opener restored");
    }

    @Test
    void insertedLineMatchesAFreshHighlighterAndRetokenizesOnlyTheInsertedRow() {
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(CORPUS, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        String edited = CORPUS.replace("    int count = 1;", "    int count = 1;\n    int extra = 2;");
        List<Line> incremental = warm.highlight(edited, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(1, warm.lastTokenizedLines(), "only the inserted row");
        assertTokensMatchAFreshHighlighter(incremental, edited, "inserted row");
    }

    @Test
    void deletedLineMatchesAFreshHighlighterAndShiftsTheTailByReference() {
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(CORPUS, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        String edited = CORPUS.replace("    int count = 1;\n", "");
        List<Line> incremental = warm.highlight(edited, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(0, warm.lastTokenizedLines(), "the tail below the removed row shifts by reference");
        assertTokensMatchAFreshHighlighter(incremental, edited, "deleted row");
    }

    @Test
    void replacedBlockMatchesAFreshHighlighterAndRetokenizesOnlyTheReplacedRows() {
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(CORPUS, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        String edited = CORPUS.replace("        text // not a comment\n        \"\"\";\n",
            "        text inside\n        the block\n        \"\"\";\n");
        List<Line> incremental = warm.highlight(edited, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(2, warm.lastTokenizedLines(), "two rows replaced inside an open text block");
        assertTokensMatchAFreshHighlighter(incremental, edited, "replaced block");
    }

    @Test
    void insertedCommentCloserPropagatesTheStateChangeIntoTheShiftedTail() {
        String text = "a\n/* open\nb\nc\nd\ne";
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        String edited = text.replace("b\n", "b\n*/\n");
        List<Line> incremental = warm.highlight(edited, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(4, warm.lastTokenizedLines(), "the closer plus the tail rows kept in comment state");
        assertTokensMatchAFreshHighlighter(incremental, edited, "comment closer inserted");
    }

    @Test
    void deletedCommentOpenerRetokenizesOnlyUntilTheStateConverges() {
        String text = "a\n/* open\nb\nc */\nd\ne";
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        String edited = text.replace("/* open\n", "");
        List<Line> incremental = warm.highlight(edited, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(2, warm.lastTokenizedLines(), "the rows the comment state no longer covers");
        assertTokensMatchAFreshHighlighter(incremental, edited, "comment opener deleted");
    }

    @Test
    void sameSizeDocumentWithTwoEditedRowsRetokenizesOnlyTheEditedRows() {
        StringBuilder builder = new StringBuilder();
        for (int row = 0; row < 1_000; row++) {
            builder.append("int row_").append(row).append(" = ").append(row).append(";\n");
        }
        String text = builder.toString();
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertTrue(text.contains("int row_10 = 10;") && text.contains("int row_900 = 900;"));
        String edited = text.replace("int row_10 = 10;", "int row_ten = 10;")
            .replace("int row_900 = 900;", "int row_nine_hundred = 900;");
        List<Line> incremental = warm.highlight(edited, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(2, warm.lastTokenizedLines(), "the unchanged rows between the two edits stay cached");
        assertTokensMatchAFreshHighlighter(incremental, edited, "two same-size edits");
    }

    @Test
    void oneInsertedLineInATenThousandLineDocumentRetokenizesOnlyThatLine() {
        StringBuilder builder = new StringBuilder();
        for (int row = 0; row < 10_000; row++) {
            builder.append("int value_").append(row).append(" = ").append(row).append(";\n");
        }
        String text = builder.toString();
        LineCachedSyntaxHighlighter warm = new LineCachedSyntaxHighlighter();
        warm.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        String anchor = "int value_5000 = 5000;";
        Assertions.assertTrue(text.contains(anchor));
        String edited = text.replace(anchor, anchor + "\nint inserted = 42;");
        List<Line> incremental = warm.highlight(edited, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(1, warm.lastTokenizedLines(), "an inserted line must not cascade re-tokenization below");
        assertTokensMatchAFreshHighlighter(incremental, edited, "10k line insertion");
    }

    @Test
    void keywordsBeforeParenthesisAreKeywords() {
        List<Line> lines = new LineCachedSyntaxHighlighter().highlight(String.join("\n",
            "if (a) {",
            "    for (int i = 0; i < 1; i++) {",
            "        while (ready) {",
            "            return (x);",
            "        }",
            "    }",
            "    foo(a);"), LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);

        Style keyword = SyntaxTheme.DEFAULTS.style(TokenType.KEYWORD, Style.EMPTY);
        Style function = SyntaxTheme.DEFAULTS.style(TokenType.FUNCTION, Style.EMPTY);
        Assertions.assertNotEquals(keyword, function);
        Assertions.assertEquals(keyword, styleAt(lines.get(0), 0), "if");
        Assertions.assertEquals(keyword, styleAt(lines.get(1), 4), "for");
        Assertions.assertEquals(keyword, styleAt(lines.get(2), 8), "while");
        Assertions.assertEquals(keyword, styleAt(lines.get(3), 12), "return");
        Assertions.assertEquals(function, styleAt(lines.get(6), 4), "foo call");
    }

    @Test
    void forwardSelectionOnOneRowPaintsTheCharactersBetweenAnchorAndCursor() {
        String text = "hello world";
        List<Line> plain = plainLines(text);
        List<Line> selected = selectedLines(text, SelectionCursor.collapsed(0, 2).extendTo(0, 7));
        assertSelectedRange(plain.get(0), selected.get(0), 2, 7);
    }

    @Test
    void backwardSelectionOnOneRowIsPainted() {
        String text = "hello world";
        List<Line> plain = plainLines(text);
        List<Line> selected = selectedLines(text, SelectionCursor.collapsed(0, 9).extendTo(0, 3));
        assertSelectedRange(plain.get(0), selected.get(0), 3, 9);
    }

    @Test
    void upwardSelectionAcrossRowsPaintsEachRowFromItsOwnBound() {
        String text = "row zero text\nrow one text";
        List<Line> plain = plainLines(text);
        List<Line> selected = selectedLines(text, SelectionCursor.collapsed(1, 3).extendTo(0, 2));
        assertSelectedRange(plain.get(0), selected.get(0), 2, Integer.MAX_VALUE);
        assertSelectedRange(plain.get(1), selected.get(1), 0, 3);
    }

    @Test
    void middleRowsAreFullyPaintedAndRowsOutsideTheSelectionAreUntouched() {
        String text = "aaaa\nbbbb\ncccc\ndddd";
        List<Line> plain = plainLines(text);
        List<Line> selected = selectedLines(text, SelectionCursor.collapsed(1, 2).extendTo(2, 1));
        assertSelectedRange(plain.get(0), selected.get(0), 0, 0);
        assertSelectedRange(plain.get(1), selected.get(1), 2, Integer.MAX_VALUE);
        assertSelectedRange(plain.get(2), selected.get(2), 0, 1);
        assertSelectedRange(plain.get(3), selected.get(3), 0, 0);
    }

    @Test
    void collapsedSelectionPaintsNothing() {
        String text = CORPUS;
        List<Line> plain = plainLines(text);
        List<Line> collapsed = selectedLines(text, SelectionCursor.collapsed(4, 6));
        Assertions.assertEquals(pieces(plain), pieces(collapsed));
    }

    @Test
    void diagnosticTintAppliesToItsRowOnlyAndClearsWhenRemoved() {
        String text = "one\ntwo\nthree";
        LineCachedSyntaxHighlighter highlighter = new LineCachedSyntaxHighlighter();
        List<Line> plain = highlighter.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);

        highlighter.setDiagnostics(List.of(new LineCachedSyntaxHighlighter.Diagnostic(1,
            LineCachedSyntaxHighlighter.Severity.ERROR)));
        List<Line> tinted = highlighter.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(pieces(List.of(plain.get(0), plain.get(2))),
            pieces(List.of(tinted.get(0), tinted.get(2))));
        for (int col = 0; col < lengthOf(plain.get(1)); col++) {
            Assertions.assertEquals(styleAt(plain.get(1), col).bg(ERROR_BACKGROUND), styleAt(tinted.get(1), col),
                "tint column " + col);
        }

        highlighter.setDiagnostics(List.of());
        List<Line> cleared = highlighter.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(pieces(plain), pieces(cleared));
    }

    @Test
    void unchangedRowsReuseTheSameLineInstance() {
        LineCachedSyntaxHighlighter highlighter = new LineCachedSyntaxHighlighter();
        List<Line> first = highlighter.highlight(CORPUS, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        List<Line> second = highlighter.highlight(CORPUS, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        for (int row = 0; row < first.size(); row++) {
            Assertions.assertSame(first.get(row), second.get(row), "row " + row);
        }
    }

    @Test
    void spansAlwaysReconstructTheSourceLine() {
        String text = String.join("\n",
            "class T {",
            "\tString s = \"unterminated;",
            "\tint x = 0x1F @ 3;",
            "\tfloat f = 1e-5f;",
            "\t@Override void m() { /* open",
            "   ",
            "x @",
            "}");
        List<Line> lines = new LineCachedSyntaxHighlighter().highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        String[] sources = text.split("\n", -1);
        Assertions.assertEquals(sources.length, lines.size());
        for (int row = 0; row < sources.length; row++) {
            Assertions.assertEquals(sources[row], contentOf(lines.get(row)), "row " + row);
        }
    }

    private static void assertWarmMatchesFresh(LineCachedSyntaxHighlighter warm, String text, String step) {
        List<Line> incremental = warm.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(pieces(fresh(text)), pieces(incremental), "warm and fresh differ after edit: " + step);
    }

    private static void assertTokensMatchAFreshHighlighter(List<Line> incremental, String text, String step) {
        Assertions.assertEquals(pieces(fresh(text)), pieces(incremental), "warm and fresh differ: " + step);
    }

    private static List<Line> fresh(String text) {
        return new LineCachedSyntaxHighlighter().highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
    }

    private static List<Line> plainLines(String text) {
        return new LineCachedSyntaxHighlighter().highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
    }

    private static List<Line> selectedLines(String text, SelectionCursor selection) {
        LineCachedSyntaxHighlighter highlighter = new LineCachedSyntaxHighlighter();
        highlighter.setSelection(selection);
        return highlighter.highlight(text, LANGUAGE, Style.EMPTY, SyntaxTheme.DEFAULTS);
    }

    private static void assertSelectedRange(Line plain, Line selected, int from, int to) {
        Assertions.assertEquals(lengthOf(plain), lengthOf(selected));
        for (int col = 0; col < lengthOf(plain); col++) {
            Style base = styleAt(plain, col);
            Style expected = col >= from && col < to ? base.reversed() : base;
            Assertions.assertEquals(expected, styleAt(selected, col), "column " + col);
        }
    }

    private static Style styleAt(Line line, int column) {
        int cursor = 0;
        for (Span span : line.spans()) {
            cursor += span.content().length();
            if (column < cursor) {
                return span.style();
            }
        }
        throw new AssertionError("column " + column + " is outside the line");
    }

    private static int lengthOf(Line line) {
        return contentOf(line).length();
    }

    private static String contentOf(Line line) {
        StringBuilder content = new StringBuilder();
        for (Span span : line.spans()) {
            content.append(span.content());
        }
        return content.toString();
    }

    private static List<List<Piece>> pieces(List<Line> lines) {
        List<List<Piece>> out = new ArrayList<>(lines.size());
        for (Line line : lines) {
            List<Piece> row = new ArrayList<>();
            for (Span span : line.spans()) {
                row.add(new Piece(span.content(), span.style()));
            }
            out.add(row);
        }
        return out;
    }

    private record Edit(String from, String to) {
    }

    private record Piece(String content, Style style) {
    }
}
