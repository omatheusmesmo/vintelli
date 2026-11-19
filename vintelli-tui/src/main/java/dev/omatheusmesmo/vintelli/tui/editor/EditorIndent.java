package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.FormattingOptions;

import java.util.Map;
import java.util.TreeMap;

/**
 * Describes the indent a document uses, for the status bar and for the formatting
 * request. Only the first lines are read, so the status bar does not scan a large
 * file on every frame. A single space at the start of a line is ignored, because
 * it is usually a Javadoc or comment continuation.
 */
final class EditorIndent {

    private static final int SAMPLE_LINES = 400;
    private static final int DEFAULT_TAB_SIZE = 4;

    private EditorIndent() {
    }

    /**
     * {@code Tab} when tab-indented lines outnumber space-indented ones,
     * {@code N spaces} for the smallest indent of two or more spaces that
     * repeats, or an empty string when no line is indented.
     */
    static String describe(String text) {
        Indent indent = detect(text);
        if (indent.tabs()) {
            return "Tab";
        }
        return indent.width() == 0 ? "" : indent.width() + " spaces";
    }

    /**
     * The options of a formatting request for the document: the indent the document already uses, so the server
     * formats it the way it is written. A document without an indent gets four spaces.
     */
    static FormattingOptions formattingOptions(String text) {
        Indent indent = detect(text);
        if (indent.tabs()) {
            return new FormattingOptions(DEFAULT_TAB_SIZE, false);
        }
        return new FormattingOptions(indent.width() == 0 ? DEFAULT_TAB_SIZE : indent.width(), true);
    }

    /**
     * @param tabs whether tab-indented lines outnumber space-indented ones
     * @param width the smallest space indent of two or more spaces that repeats, zero when there is none
     */
    private record Indent(boolean tabs, int width) {
    }

    private static Indent detect(String text) {
        int tabLines = 0;
        int spaceLines = 0;
        Map<Integer, Integer> widths = new TreeMap<>();
        int start = 0;
        for (int line = 0; line < SAMPLE_LINES && start < text.length(); line++) {
            int end = text.indexOf('\n', start);
            String content = end < 0 ? text.substring(start) : text.substring(start, end);
            start = end < 0 ? text.length() : end + 1;
            if (content.isBlank()) {
                continue;
            }
            if (content.charAt(0) == '\t') {
                tabLines++;
                continue;
            }
            int spaces = 0;
            while (spaces < content.length() && content.charAt(spaces) == ' ') {
                spaces++;
            }
            if (spaces > 0) {
                spaceLines++;
            }
            if (spaces >= 2) {
                widths.merge(spaces, 1, Integer::sum);
            }
        }
        if (tabLines > spaceLines) {
            return new Indent(true, 0);
        }
        for (Map.Entry<Integer, Integer> width : widths.entrySet()) {
            if (width.getValue() >= 2) {
                return new Indent(false, width.getKey());
            }
        }
        return new Indent(false, 0);
    }
}
