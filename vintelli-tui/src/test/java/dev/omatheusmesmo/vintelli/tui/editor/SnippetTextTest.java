package dev.omatheusmesmo.vintelli.tui.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnippetTextTest {

    @Test
    void placeholdersKeepTheirDefaultTextAndTheCaretGoesToTheFirstStop() {
        SnippetText.Expansion expansion = SnippetText.expand("alpha(${1:value}) $0");

        assertEquals("alpha(value) ", expansion.text());
        assertEquals(6, expansion.caret(), "the caret sits where the first placeholder starts");
    }

    @Test
    void theFinalStopIsTheCaretWhenThereIsNoNumberedStop() {
        SnippetText.Expansion expansion = SnippetText.expand("list.add($0)");

        assertEquals("list.add()", expansion.text());
        assertEquals(9, expansion.caret());
    }

    @Test
    void theLowestNumberedStopWinsOverTheOrderTheSnippetWritesThem() {
        SnippetText.Expansion expansion = SnippetText.expand("${2:b} ${1:a}");

        assertEquals("b a", expansion.text());
        assertEquals(2, expansion.caret(), "$1 comes first, whatever the position of $2");
    }

    @Test
    void aMultiLineSnippetKeepsItsLayoutAndTheCaretOfTheFirstStop() {
        SnippetText.Expansion expansion = SnippetText.expand("for (${1:int} ${2:i} = 0; $2 < ${3:n}; $2++) {\n\t$0\n}");

        assertEquals("for (int i = 0; i < n; i++) {\n\t\n}", expansion.text());
        assertEquals(5, expansion.caret());
    }

    @Test
    void choicesKeepTheirFirstOptionAndEscapesStayLiteral() {
        assertEquals("one", SnippetText.expand("${1|one,two|}").text());
        assertEquals("$5 and \\", SnippetText.expand("\\$5 and \\\\").text());
    }

    @Test
    void variablesVanishAndATextWithoutStopsPutTheCaretAtTheEnd() {
        SnippetText.Expansion variable = SnippetText.expand("${TM_FILENAME}x");
        assertEquals("x", variable.text());

        SnippetText.Expansion plain = SnippetText.expand("plain text");
        assertEquals("plain text", plain.text());
        assertEquals(10, plain.caret());
    }

    @Test
    void aDollarThatStartsNothingStaysAsItIs() {
        assertEquals("a$", SnippetText.expand("a$").text());
        assertEquals("$ x", SnippetText.expand("$ x").text());
    }
}
