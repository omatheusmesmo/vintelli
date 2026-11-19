package dev.omatheusmesmo.vintelli.tui.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes the SGR sequences TamboUI emits readable by terminals such as foot.
 * Each {@code ESC [ params m} sequence gets two rules:
 *
 * <ul>
 * <li>An underline color written as {@code 58;2;R;G;B} or {@code 58;5;N} becomes
 * the colon form {@code 58:2::R:G:B} or {@code 58:5:N}, which ECMA-48 and ITU
 * T.416 define and which occupies a single parameter.</li>
 * <li>A sequence with more than 16 parameters is split into consecutive
 * sequences of at most 16, because foot keeps only 16 parameters per sequence
 * and drops the rest. A cut falls between attributes only, never inside a
 * {@code 38}, {@code 48} or {@code 58} color group, and a colon group counts as
 * one parameter. The sequences apply in order, so the leading reset stays in
 * the first one.</li>
 * </ul>
 *
 * <p>Everything else passes through byte for byte, including text outside escape
 * sequences and sequences that need neither rule. A sequence that needs no rule
 * is never copied.
 */
public final class SgrCompatibility {

    static final int MAX_PARAMETERS = 16;

    private static final char ESCAPE = '\u001b';
    private static final char SEPARATOR = ';';
    private static final char FINAL_SGR = 'm';
    private static final String UNDERLINE = "58";
    private static final String TRUECOLOR = "2";
    private static final String INDEXED = "5";
    private static final int TRUECOLOR_SPAN = 5;
    private static final int INDEXED_SPAN = 3;

    private SgrCompatibility() {
    }

    /**
     * Returns the output with every SGR sequence rewritten to the form terminals
     * read. The input instance is returned when no sequence needs a rewrite.
     */
    public static String forTerminals(String output) {
        StringBuilder rewritten = null;
        int copied = 0;
        int escape = output.indexOf(ESCAPE);
        while (escape >= 0 && escape + 1 < output.length()) {
            if (output.charAt(escape + 1) != '[') {
                escape = output.indexOf(ESCAPE, escape + 1);
                continue;
            }
            int start = escape + 2;
            int end = start;
            while (end < output.length() && isParameter(output.charAt(end))) {
                end++;
            }
            if (end < output.length() && output.charAt(end) == FINAL_SGR && needsRewrite(output, start, end)) {
                if (rewritten == null) {
                    rewritten = new StringBuilder(output.length() + 32);
                }
                rewritten.append(output, copied, escape).append(sequences(output.substring(start, end)));
                copied = end + 1;
                escape = output.indexOf(ESCAPE, copied);
            } else {
                escape = output.indexOf(ESCAPE, escape + 1);
            }
        }
        return rewritten == null ? output : rewritten.append(output, copied, output.length()).toString();
    }

    private static boolean isParameter(char character) {
        return (character >= '0' && character <= '9') || character == SEPARATOR || character == ':';
    }

    private static boolean needsRewrite(String output, int start, int end) {
        int separators = 0;
        boolean underline = false;
        for (int index = start; index < end; index++) {
            if (output.charAt(index) == SEPARATOR) {
                separators++;
            } else if (index + 2 < end && output.charAt(index) == '5' && output.charAt(index + 1) == '8'
                    && output.charAt(index + 2) == SEPARATOR) {
                underline = true;
            }
        }
        return underline || separators >= MAX_PARAMETERS;
    }

    private static String sequences(String parameters) {
        String[] tokens = parameters.split(";", -1);
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder(parameters.length() + 8);
        int chunkParameters = 0;
        int index = 0;
        while (index < tokens.length) {
            int span = extendedColorSpan(tokens, index);
            String colon = span > 1 && UNDERLINE.equals(tokens[index]) ? underlineColon(tokens, index, span) : null;
            int unitParameters = colon == null ? span : 1;
            if (chunkParameters > 0 && chunkParameters + unitParameters > MAX_PARAMETERS) {
                chunks.add(chunk.toString());
                chunk.setLength(0);
                chunkParameters = 0;
            }
            if (chunkParameters > 0) {
                chunk.append(SEPARATOR);
            }
            if (colon != null) {
                chunk.append(colon);
            } else {
                for (int token = index; token < index + span; token++) {
                    if (token > index) {
                        chunk.append(SEPARATOR);
                    }
                    chunk.append(tokens[token]);
                }
            }
            chunkParameters += unitParameters;
            index += span;
        }
        chunks.add(chunk.toString());
        StringBuilder sequences = new StringBuilder(parameters.length() + 8 * chunks.size());
        for (String text : chunks) {
            sequences.append(ESCAPE).append('[').append(text).append(FINAL_SGR);
        }
        return sequences.toString();
    }

    private static int extendedColorSpan(String[] tokens, int index) {
        String token = tokens[index];
        if (!"38".equals(token) && !"48".equals(token) && !UNDERLINE.equals(token)) {
            return 1;
        }
        if (index + 1 < tokens.length && TRUECOLOR.equals(tokens[index + 1])
                && index + TRUECOLOR_SPAN - 1 < tokens.length
                && isDecimal(tokens[index + 2]) && isDecimal(tokens[index + 3]) && isDecimal(tokens[index + 4])) {
            return TRUECOLOR_SPAN;
        }
        if (index + 1 < tokens.length && INDEXED.equals(tokens[index + 1])
                && index + INDEXED_SPAN - 1 < tokens.length && isDecimal(tokens[index + 2])) {
            return INDEXED_SPAN;
        }
        return 1;
    }

    private static String underlineColon(String[] tokens, int index, int span) {
        if (span == TRUECOLOR_SPAN) {
            return "58:2::" + tokens[index + 2] + ":" + tokens[index + 3] + ":" + tokens[index + 4];
        }
        return "58:5:" + tokens[index + 2];
    }

    private static boolean isDecimal(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int position = 0; position < token.length(); position++) {
            char character = token.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
