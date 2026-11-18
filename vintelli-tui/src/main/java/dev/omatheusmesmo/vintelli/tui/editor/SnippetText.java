package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.HashMap;
import java.util.Map;

/**
 * Reduces the snippet syntax of a completion insert text to the plain text it stands for. Tab stops
 * ({@code $1}, {@code ${1}}) and placeholders ({@code ${1:name}}) keep their default text, choices
 * ({@code ${1|a,b|}}) keep their first choice, variables ({@code $NAME}) become empty and the final stop
 * {@code $0} marks where the caret ends. The caret goes to the first tab stop, the lowest number above zero,
 * or to {@code $0}, or to the end of the text when the snippet has no stop at all.
 */
final class SnippetText {

    /**
     * @param text the plain text to insert
     * @param caret offset of the caret inside {@code text}
     */
    record Expansion(String text, int caret) {
    }

    private final String source;
    private final StringBuilder out = new StringBuilder();
    private int index;
    private int firstNumber = Integer.MAX_VALUE;
    private int firstStop = -1;
    private int finalStop = -1;
    private final Map<Integer, String> defaults = new HashMap<>();

    private SnippetText(String source) {
        this.source = source;
    }

    static Expansion expand(String snippet) {
        SnippetText expander = new SnippetText(snippet);
        expander.readUntil(-1);
        int caret = expander.firstStop >= 0 ? expander.firstStop
                : expander.finalStop >= 0 ? expander.finalStop : expander.out.length();
        return new Expansion(expander.out.toString(), caret);
    }

    /**
     * Copies the text up to the closing brace of the current placeholder, or to the end when the text is
     * not nested in one, and resolves every construct on the way.
     */
    private void readUntil(int closing) {
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == closing) {
                index++;
                return;
            }
            if (current == '\\' && index + 1 < source.length() && "$}\\".indexOf(source.charAt(index + 1)) >= 0) {
                out.append(source.charAt(index + 1));
                index += 2;
            } else if (current == '$') {
                readDollar();
            } else {
                out.append(current);
                index++;
            }
        }
    }

    /**
     * Reads a construct that starts with a dollar sign. A bare {@code $N} repeats the default text of the
     * placeholder {@code N} when the snippet names one before it, the way the editor mirrors a tab stop.
     */
    private void readDollar() {
        index++;
        if (index >= source.length()) {
            out.append('$');
            return;
        }
        char next = source.charAt(index);
        if (Character.isDigit(next)) {
            int number = readNumber();
            stop(number, out.length());
            out.append(defaults.getOrDefault(number, ""));
        } else if (next == '{') {
            index++;
            readBraced();
        } else if (Character.isLetter(next) || next == '_') {
            while (index < source.length() && (Character.isLetterOrDigit(source.charAt(index))
                    || source.charAt(index) == '_')) {
                index++;
            }
        } else {
            out.append('$');
        }
    }

    private void readBraced() {
        if (index >= source.length()) {
            return;
        }
        if (Character.isDigit(source.charAt(index))) {
            int number = readNumber();
            int position = out.length();
            if (index < source.length() && source.charAt(index) == ':') {
                index++;
                stop(number, position);
                readUntil('}');
                defaults.putIfAbsent(number, out.substring(position));
            } else if (index < source.length() && source.charAt(index) == '|') {
                stop(number, position);
                readChoice();
            } else {
                stop(number, position);
                skipTo('}');
            }
        } else {
            skipTo('}');
        }
    }

    private void readChoice() {
        index++;
        int end = source.indexOf('|', index);
        int stop = end < 0 ? source.length() : end;
        int comma = source.indexOf(',', index);
        int choiceEnd = comma < 0 || comma > stop ? stop : comma;
        out.append(source, index, choiceEnd);
        index = end < 0 ? source.length() : end + 1;
        skipTo('}');
    }

    private void skipTo(char closing) {
        int end = source.indexOf(closing, index);
        index = end < 0 ? source.length() : end + 1;
    }

    private int readNumber() {
        int number = 0;
        while (index < source.length() && Character.isDigit(source.charAt(index))) {
            number = Math.clamp(number * 10L + Character.digit(source.charAt(index), 10), 0, Integer.MAX_VALUE);
            index++;
        }
        return number;
    }

    private void stop(int number, int position) {
        if (number == 0) {
            if (finalStop < 0) {
                finalStop = position;
            }
        } else if (number < firstNumber) {
            firstNumber = number;
            firstStop = position;
        }
    }
}
