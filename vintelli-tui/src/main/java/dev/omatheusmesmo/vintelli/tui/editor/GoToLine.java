package dev.omatheusmesmo.vintelli.tui.editor;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A caret target typed into the Go to Line overlay: a one based line, and a one based column
 * (1 when the input names only the line). The numbers are not checked against the document;
 * the editor clamps them to the text it holds.
 */
public record GoToLine(int line, int column) {

    private static final Pattern FORM = Pattern.compile("(\\d+)(?::(\\d+))?");

    /**
     * Parses {@code line} or {@code line:column}, surrounding spaces ignored. Empty, zero,
     * non numeric and malformed input is refused; very large numbers saturate instead of failing.
     */
    public static Optional<GoToLine> parse(String input) {
        Matcher matcher = FORM.matcher(input == null ? "" : input.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int line = number(matcher.group(1));
        int column = matcher.group(2) == null ? 1 : number(matcher.group(2));
        if (line < 1 || column < 1) {
            return Optional.empty();
        }
        return Optional.of(new GoToLine(line, column));
    }

    private static int number(String digits) {
        return new BigInteger(digits).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }
}
