package dev.omatheusmesmo.vintelli.tui;

import java.io.Console;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;

/** Chooses the project tree's line characters independently from its file icons. */
final class ProjectTreeGlyphs {

    private static final String BOX_DRAWING = "│├──└──";

    private ProjectTreeGlyphs() {
    }

    static boolean supportsUnicode() {
        Console console = System.console();
        Charset outputCharset = console == null ? System.out.charset() : console.charset();
        return supportsUnicode(System.getenv(), outputCharset);
    }

    static boolean supportsUnicode(Map<String, String> environment, Charset outputCharset) {
        if ("dumb".equalsIgnoreCase(environment.get("TERM")) || outputCharset == null
                || !outputCharset.newEncoder().canEncode(BOX_DRAWING)) {
            return false;
        }
        String locale = activeLocale(environment);
        return locale == null || usesUtf8(locale);
    }

    private static String activeLocale(Map<String, String> environment) {
        for (String key : new String[] { "LC_ALL", "LC_CTYPE", "LANG" }) {
            String value = environment.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean usesUtf8(String locale) {
        String normalized = locale.toLowerCase(Locale.ROOT);
        int encodingStart = normalized.lastIndexOf('.') + 1;
        String encoding = normalized.substring(encodingStart);
        int modifierStart = encoding.indexOf('@');
        if (modifierStart >= 0) {
            encoding = encoding.substring(0, modifierStart);
        }
        encoding = encoding.replace("-", "").replace("_", "");
        return encoding.equals("utf8");
    }
}
