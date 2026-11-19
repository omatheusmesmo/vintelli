package dev.omatheusmesmo.vintelli.tui.editor;

import dev.tamboui.style.Style;
import dev.tamboui.widgets.syntax.SyntaxTheme;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BraceMatcherTest {

    private static final String CALL = "foo(bar(1), 2)";

    @Test
    void caretAfterTheClosingParenthesisPairsWithTheOpenerAfterFoo() {
        assertEquals(Optional.of(new BraceMatcher.BracePair(0, 13, 0, 3)), matchAt(CALL, 0, 14));
    }

    @Test
    void caretAfterAnInnerClosingParenthesisSkipsTheOuterPair() {
        assertEquals(Optional.of(new BraceMatcher.BracePair(0, 9, 0, 7)), matchAt(CALL, 0, 10));
    }

    @Test
    void caretBeforeAnOpeningParenthesisPairsWithItsCloser() {
        assertEquals(Optional.of(new BraceMatcher.BracePair(0, 3, 0, 13)), matchAt(CALL, 0, 3));
    }

    @Test
    void everyBracketKindPairsOnlyWithItsOwnKind() {
        assertEquals(Optional.of(new BraceMatcher.BracePair(0, 0, 0, 4)), matchAt("[{x}]", 0, 1));
        assertEquals(Optional.of(new BraceMatcher.BracePair(0, 1, 0, 3)), matchAt("[{x}]", 0, 2),
                "the brace pairs with its own closer, not with the square bracket around it");
        assertEquals(Optional.of(new BraceMatcher.BracePair(0, 1, 0, 3)), matchAt("{(x)}", 0, 2),
                "the parenthesis pairs with its own closer despite the brace around it");
        assertEquals(Optional.empty(), matchAt("{(x]}", 0, 3), "a closer of another kind does not balance");
    }

    @Test
    void bracketInsideAStringIsNeitherTheTargetNorThePartner() {
        String text = "f(\")\")";

        assertEquals(Optional.of(new BraceMatcher.BracePair(0, 1, 0, 5)), matchAt(text, 0, 2),
                "the opener skips the bracket inside the string and pairs with the real closer");
        assertEquals(Optional.empty(), matchAt(text, 0, 4), "the caret after a string bracket matches nothing");
    }

    @Test
    void bracketInsideABlockCommentIsIgnored() {
        assertEquals(Optional.empty(), matchAt("/* ( */", 0, 4));
    }

    @Test
    void bracketInsideATextBlockIsIgnored() {
        String text = "String t = \"\"\"\n(\n\"\"\";";

        assertEquals(Optional.empty(), matchAt(text, 1, 1));
    }

    @Test
    void unmatchedBracketHasNoPartnerAndDoesNotThrow() {
        assertEquals(Optional.empty(), matchAt("foo(bar", 0, 4));
        assertEquals(Optional.empty(), matchAt(")", 0, 1));
        assertEquals(Optional.empty(), matchAt("", 0, 0));
        assertEquals(Optional.empty(), matchAt("x", 5, 0), "a caret outside the text matches nothing");
    }

    @Test
    void forwardSearchStopsAfterTheLineCap() {
        assertTrue(BraceMatcher.find(spanned(BraceMatcher.MAX_LINES + 1), (row, column) -> true, 0, 1).isPresent(),
                "a closer on the last allowed line is found");
        assertFalse(BraceMatcher.find(spanned(BraceMatcher.MAX_LINES + 2), (row, column) -> true, 0, 1).isPresent(),
                "a closer one line beyond the cap is not found");
    }

    @Test
    void backwardSearchStopsAfterTheLineCap() {
        int last = BraceMatcher.MAX_LINES;
        assertTrue(BraceMatcher.find(spanned(last + 1), (row, column) -> true, last, 1).isPresent(),
                "an opener on the first allowed line is found");
        assertFalse(BraceMatcher.find(spanned(last + 2), (row, column) -> true, last + 1, 1).isPresent(),
                "an opener one line beyond the cap is not found");
    }

    private static Optional<BraceMatcher.BracePair> matchAt(String text, int row, int column) {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        highlighter.highlight(text, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        return BraceMatcher.find(List.of(text.split("\n", -1)), highlighter::isCodeBracket, row, column);
    }

    private static List<String> spanned(int rows) {
        List<String> lines = new ArrayList<>(Collections.nCopies(rows, ""));
        lines.set(0, "(");
        lines.set(rows - 1, ")");
        return lines;
    }
}
