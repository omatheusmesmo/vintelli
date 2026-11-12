package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

/**
 * Lines of the Problems window: one problem with its severity glyph, line and column, and its message wrapped to
 * the pane width under the message column; plus the plain lines of states and file groups. Styles are looked up by
 * TCSS role name through the function the caller passes in.
 */
final class ProblemRows {

    /**
     * One rendered line. {@code problemIndex} is the index of the problem the line belongs to, or -1 for a line that
     * selects nothing (file names, states, skipped providers).
     */
    record Row(Line line, int problemIndex) {
    }

    private static final int MIN_MESSAGE_WIDTH = 20;
    private static final int CONTINUATION_INDENT = 4;
    private static final String NERD_ERROR = "";
    private static final String NERD_WARNING = "";
    private static final String NERD_INFORMATION = "";
    private static final String NERD_HINT = "";

    private ProblemRows() {
    }

    static List<Row> problem(int index, Diagnostic diagnostic, boolean selected, int width, IconMode mode,
            Function<String, Style> roles) {
        String marker = selected ? ">" : " ";
        String lead = marker + " " + glyph(diagnostic.severity(), mode) + " " + severityWord(diagnostic.severity());
        String location = "L" + (diagnostic.startLine() + 1) + ":C" + (diagnostic.startColumn() + 1);
        String source = diagnostic.source().isBlank() ? "diagnostic" : diagnostic.source();
        int messageIndent = CharWidth.of(lead + "  " + location + "  " + source + "  ");
        String message = singleLine(diagnostic.message());
        Style messageStyle = roles.apply(selected ? "tool-window-title" : "tool-window");
        List<Row> rows = new ArrayList<>();
        if (width - messageIndent >= MIN_MESSAGE_WIDTH) {
            List<String> pieces = wrap(message, width - messageIndent, width - messageIndent);
            rows.add(new Row(Line.from(
                    Span.styled(lead, roles.apply(severityRole(diagnostic.severity()))),
                    Span.styled("  " + location, roles.apply("problem-location")),
                    Span.styled("  " + source + "  ", roles.apply("problem-source")),
                    Span.styled(pieces.getFirst(), messageStyle)), index));
            for (String piece : pieces.subList(1, pieces.size())) {
                rows.add(new Row(Line.from(Span.styled(" ".repeat(messageIndent) + piece, messageStyle)), index));
            }
        } else {
            rows.add(new Row(Line.from(
                    Span.styled(lead, roles.apply(severityRole(diagnostic.severity()))),
                    Span.styled("  " + location, roles.apply("problem-location")),
                    Span.styled("  " + source, roles.apply("problem-source"))), index));
            for (String piece : wrap(message, width - CONTINUATION_INDENT, width - CONTINUATION_INDENT)) {
                rows.add(new Row(Line.from(Span.styled(" ".repeat(CONTINUATION_INDENT) + piece, messageStyle)),
                        index));
            }
        }
        return rows;
    }

    static List<Row> plain(String text, int width, Style style) {
        List<Row> rows = new ArrayList<>();
        for (String piece : wrap(singleLine(text), width, width)) {
            rows.add(new Row(Line.from(Span.styled(piece, style)), -1));
        }
        return rows;
    }

    /**
     * Greedy word wrap by display width. A word wider than the line is cut inside the word, so no row is wider than
     * its limit. The first piece uses {@code firstWidth} and every later piece {@code nextWidth}.
     */
    static List<String> wrap(String text, int firstWidth, int nextWidth) {
        List<String> pieces = new ArrayList<>();
        String rest = text == null ? "" : text;
        int limit = Math.max(1, firstWidth);
        while (CharWidth.of(rest) > limit) {
            int cut = breakPoint(rest, limit);
            pieces.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
            limit = Math.max(1, nextWidth);
        }
        pieces.add(rest);
        return pieces;
    }

    static String glyph(Severity severity, IconMode mode) {
        if (mode == IconMode.ASCII) {
            return switch (severity) {
                case ERROR -> "x";
                case WARNING -> "!";
                case INFORMATION -> "i";
                case HINT -> "?";
            };
        }
        return switch (severity) {
            case ERROR -> NERD_ERROR;
            case WARNING -> NERD_WARNING;
            case INFORMATION -> NERD_INFORMATION;
            case HINT -> NERD_HINT;
        };
    }

    static String severityWord(Severity severity) {
        return switch (severity) {
            case ERROR -> "ERROR";
            case WARNING -> "WARN ";
            case INFORMATION -> "INFO ";
            case HINT -> "HINT ";
        };
    }

    private static String severityRole(Severity severity) {
        return switch (severity) {
            case ERROR -> "problem-error";
            case WARNING -> "problem-warning";
            case INFORMATION, HINT -> "problem-info";
        };
    }

    private static int breakPoint(String text, int limit) {
        int width = 0;
        int lastSpace = -1;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int codePointWidth = CharWidth.of(new String(Character.toChars(codePoint)));
            if (width + codePointWidth > limit) {
                break;
            }
            width += codePointWidth;
            index += Character.charCount(codePoint);
            if (codePoint == ' ') {
                lastSpace = index - 1;
            }
        }
        if (index >= text.length()) {
            return text.length();
        }
        if (text.charAt(index) == ' ') {
            return index;
        }
        if (lastSpace > 0) {
            return lastSpace;
        }
        return Math.max(index, text.offsetByCodePoints(0, 1));
    }

    private static String singleLine(String value) {
        return value.replaceAll("\\R", " ").replaceAll("\\p{Cc}", " ").replaceAll("\\s+", " ").strip();
    }
}
