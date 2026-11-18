package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Face;

/**
 * Reads the HTML that Javadoc leaves in a documentation text into {@link HoverRows}: the tags that carry layout or
 * style become rows, bold and code spans, and lists; every other tag is dropped, its text kept. Entities are decoded
 * after the tags are read, so an escaped {@code &lt;T&gt;} stays text.
 */
final class HoverHtml {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9]*)[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);");

    private HoverHtml() {
    }

    static void apply(String html, HoverRows rows) {
        String markup = COMMENT.matcher(html).replaceAll("");
        Matcher tag = TAG.matcher(markup);
        int last = 0;
        while (tag.find()) {
            rows.text(decode(markup.substring(last, tag.start())));
            tag(!tag.group(1).isEmpty(), tag.group(2).toLowerCase(Locale.ROOT), rows);
            last = tag.end();
        }
        rows.text(decode(markup.substring(last)));
    }

    static String decode(String raw) {
        if (raw.indexOf('&') < 0) {
            return raw;
        }
        Matcher entity = ENTITY.matcher(raw);
        StringBuilder decoded = new StringBuilder();
        while (entity.find()) {
            entity.appendReplacement(decoded, Matcher.quoteReplacement(replacementOf(entity.group(1), entity.group(0))));
        }
        entity.appendTail(decoded);
        return decoded.toString();
    }

    private static void tag(boolean closing, String name, HoverRows rows) {
        switch (name) {
            case "p", "div", "ul", "ol", "dl", "dt", "dd", "table", "tr", "blockquote", "section", "hr" -> rows.block();
            case "h1", "h2", "h3", "h4", "h5", "h6" -> heading(closing, rows);
            case "br" -> rows.lineBreak();
            case "li" -> {
                if (closing) {
                    rows.endBlock();
                } else {
                    rows.item("- ");
                }
            }
            case "pre" -> {
                if (closing) {
                    rows.leavePreformatted();
                    rows.endBlock();
                } else {
                    rows.block();
                    rows.enterPreformatted();
                }
            }
            case "code", "tt", "kbd", "samp" -> style(closing, Face.CODE, rows);
            case "b", "strong" -> style(closing, Face.BOLD, rows);
            case "td", "th" -> rows.text(" ");
            default -> {
            }
        }
    }

    private static void heading(boolean closing, HoverRows rows) {
        if (closing) {
            rows.leave(Face.BOLD);
            rows.endBlock();
        } else {
            rows.block();
            rows.enter(Face.BOLD);
        }
    }

    private static void style(boolean closing, Face face, HoverRows rows) {
        if (closing) {
            rows.leave(face);
        } else {
            rows.enter(face);
        }
    }

    private static String replacementOf(String name, String whole) {
        return switch (name) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            default -> numericOf(name, whole);
        };
    }

    private static String numericOf(String name, String whole) {
        if (!name.startsWith("#")) {
            return whole;
        }
        boolean hex = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X');
        try {
            int codePoint = hex ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
            return Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : whole;
        } catch (NumberFormatException tooLarge) {
            return whole;
        }
    }
}
