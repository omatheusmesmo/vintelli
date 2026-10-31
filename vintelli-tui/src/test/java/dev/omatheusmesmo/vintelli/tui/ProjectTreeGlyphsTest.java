package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProjectTreeGlyphsTest {

    @Test
    void usesBoxDrawingWithUtf8LocaleAndOutput() {
        assertTrue(ProjectTreeGlyphs.supportsUnicode(
                Map.of("TERM", "xterm-256color", "LANG", "en_US.UTF-8"), StandardCharsets.UTF_8));
    }

    @Test
    void dumbTerminalKeepsTheAsciiFallback() {
        assertFalse(ProjectTreeGlyphs.supportsUnicode(
                Map.of("TERM", "dumb", "LANG", "en_US.UTF-8"), StandardCharsets.UTF_8));
    }

    @Test
    void nonUtf8ActiveLocaleOverridesUtf8OutputCharset() {
        assertFalse(ProjectTreeGlyphs.supportsUnicode(
                Map.of("TERM", "xterm-256color", "LC_ALL", "C", "LANG", "en_US.UTF-8"),
                StandardCharsets.UTF_8));
    }

    @Test
    void unrepresentableBoxDrawingFallsBackToAscii() {
        Charset outputCharset = StandardCharsets.US_ASCII;
        assertFalse(ProjectTreeGlyphs.supportsUnicode(
                Map.of("TERM", "xterm-256color", "LANG", "en_US.UTF-8"), outputCharset));
    }

    @Test
    void acceptsUtf8AliasesAndLocaleModifiers() {
        assertTrue(ProjectTreeGlyphs.supportsUnicode(
                Map.of("LC_ALL", "C.utf8", "TERM", "xterm"), StandardCharsets.UTF_8));
        assertTrue(ProjectTreeGlyphs.supportsUnicode(
                Map.of("LC_CTYPE", "en_US.UTF-8@euro", "TERM", "xterm"), StandardCharsets.UTF_8));
    }
}
