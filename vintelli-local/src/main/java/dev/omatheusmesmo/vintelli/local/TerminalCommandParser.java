package dev.omatheusmesmo.vintelli.local;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaks a terminal command line into an argv list that never goes through a
 * shell: whitespace separated words with single and double quoted words kept
 * together. No interpolation, no globbing, no command chaining: a line such
 * as {@code ls -la} becomes the two argument list {@code ls, -la}, and a
 * quoted path stays one argument.
 */
final class TerminalCommandParser {

    /**
     * @throws IllegalArgumentException when the line ends inside an
     *         unterminated quote or carries no command word
     */
    static List<String> parse(String commandLine) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char openQuote = 0;
        boolean hasWord = false;
        for (int index = 0; index < commandLine.length(); index++) {
            char c = commandLine.charAt(index);
            if (openQuote != 0) {
                if (c == openQuote) {
                    openQuote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                openQuote = c;
                hasWord = true;
            } else if (Character.isWhitespace(c)) {
                if (hasWord) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasWord = false;
                }
            } else {
                current.append(c);
                hasWord = true;
            }
        }
        if (openQuote != 0) {
            throw new IllegalArgumentException(
                    "The command line ends inside an unterminated quote");
        }
        if (hasWord) {
            tokens.add(current.toString());
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("The command line carries no command word");
        }
        return List.copyOf(tokens);
    }

    private TerminalCommandParser() {
    }
}
