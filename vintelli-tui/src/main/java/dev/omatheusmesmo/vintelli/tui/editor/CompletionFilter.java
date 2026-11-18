package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrows the suggestions by the identifier prefix typed before the caret. A suggestion whose filter text
 * (or label) starts with the prefix, ignoring case, comes first; the others follow when the prefix matches
 * their camel humps, so {@code gNa} finds {@code getName}. Within each group the server's order stays.
 */
final class CompletionFilter {

    private CompletionFilter() {
    }

    /**
     * @return the suggestions that match the prefix, prefix matches first; every suggestion when the prefix is empty
     */
    static List<Suggestion> rank(List<Suggestion> suggestions, String prefix) {
        if (prefix.isEmpty()) {
            return List.copyOf(suggestions);
        }
        List<Suggestion> prefixMatches = new ArrayList<>();
        List<Suggestion> humpMatches = new ArrayList<>();
        for (Suggestion suggestion : suggestions) {
            String text = matchedText(suggestion);
            if (text.regionMatches(true, 0, prefix, 0, prefix.length())) {
                prefixMatches.add(suggestion);
            } else if (humpMatch(prefix, text)) {
                humpMatches.add(suggestion);
            }
        }
        prefixMatches.addAll(humpMatches);
        return List.copyOf(prefixMatches);
    }

    static String matchedText(Suggestion suggestion) {
        return suggestion.filterText() == null || suggestion.filterText().isBlank()
                ? suggestion.label() : suggestion.filterText();
    }

    /**
     * Whether every word of the prefix starts a word of the text, in order, ignoring case. A word is a run of
     * characters between camel humps or underscores; a prefix word matches a text word it starts, and text words
     * may be skipped.
     */
    static boolean humpMatch(String prefix, String text) {
        List<String> wanted = words(prefix);
        List<String> available = words(text);
        if (wanted.isEmpty() || available.isEmpty()) {
            return false;
        }
        int next = 0;
        for (String word : wanted) {
            boolean found = false;
            while (!found && next < available.size()) {
                found = available.get(next++).regionMatches(true, 0, word, 0, word.length());
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '_' || character == '$') {
                flush(words, current);
                continue;
            }
            if (startsWord(text, index) && !current.isEmpty()) {
                flush(words, current);
            }
            current.append(character);
        }
        flush(words, current);
        return words;
    }

    private static boolean startsWord(String text, int index) {
        if (index == 0 || !Character.isUpperCase(text.charAt(index))) {
            return false;
        }
        char before = text.charAt(index - 1);
        boolean afterLower = Character.isLowerCase(before) || Character.isDigit(before);
        boolean endOfAcronym = Character.isUpperCase(before) && index + 1 < text.length()
                && Character.isLowerCase(text.charAt(index + 1));
        return afterLower || endOfAcronym;
    }

    private static void flush(List<String> words, StringBuilder current) {
        if (!current.isEmpty()) {
            words.add(current.toString());
            current.setLength(0);
        }
    }
}
