package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Kind;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionFilterTest {

    @Test
    void anEmptyPrefixKeepsEverySuggestionInTheServerOrder() {
        List<Suggestion> server = List.of(item("zeta", null), item("alpha", null));

        assertEquals(List.of("zeta", "alpha"), labels(CompletionFilter.rank(server, "")));
    }

    @Test
    void prefixMatchesComeFirstIgnoringCaseAndCamelHumpMatchesFollow() {
        List<Suggestion> server = List.of(item("fooGather", null), item("gather", null), item("getName", null),
                item("Gamma", null));

        assertEquals(List.of("gather", "Gamma", "fooGather"), labels(CompletionFilter.rank(server, "ga")),
                "prefix matches keep the server order, the hump match of fooGather comes after them, "
                        + "and getName matches neither");
    }

    @Test
    void camelHumpsMatchTheInitialsOfEachWord() {
        List<Suggestion> server = List.of(item("getName", null), item("getAll", null), item("gaName", null));

        assertEquals(List.of("getName", "gaName"), labels(CompletionFilter.rank(server, "gNa")),
                "gNa finds getName by its humps and gaName by the word starts ga and Name; getAll has no Na word");
    }

    @Test
    void theFilterTextIsMatchedInsteadOfTheLabelWhenTheServerSendsOne() {
        List<Suggestion> server = List.of(item("Zed", "alphaFilter"));

        assertEquals(List.of("Zed"), labels(CompletionFilter.rank(server, "alp")));
        assertTrue(CompletionFilter.rank(server, "Zed").isEmpty(),
                "the label is not what the typed text is matched against once a filter text exists");
    }

    @Test
    void aPrefixWithoutAMatchLeavesNothing() {
        assertTrue(CompletionFilter.rank(List.of(item("size", null), item("length", null)), "zz").isEmpty());
    }

    @Test
    void humpMatchingSkipsWordsAndTreatsUnderscoresAndAcronymsAsWordBoundaries() {
        assertTrue(CompletionFilter.humpMatch("Client", "HTTPClient"), "a word of the prefix skips the HTTP word");
        assertTrue(CompletionFilter.humpMatch("my_val", "myValue"));
        assertFalse(CompletionFilter.humpMatch("nN", "getName"),
                "the second prefix word needs a text word after the one the first word matched");
    }

    private static Suggestion item(String label, String filterText) {
        return new Suggestion(label, Kind.METHOD, "", null, null, false, filterText, null);
    }

    private static List<String> labels(List<Suggestion> suggestions) {
        return suggestions.stream().map(Suggestion::label).toList();
    }
}
