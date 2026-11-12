package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import org.junit.jupiter.api.Test;

class ProblemRowsTest {

    private static final Path FILE = Path.of("Broken.java");

    @Test
    void wrapBreaksAtTheLastSpaceThatFits() {
        assertEquals(List.of("alpha beta", "gamma"), ProblemRows.wrap("alpha beta gamma", 11, 11));
    }

    @Test
    void wrapCutsAWordWiderThanTheLineSoNoPieceIsWider() {
        assertEquals(List.of("abcd", "efgh", "ij"), ProblemRows.wrap("abcdefghij", 4, 4));
    }

    @Test
    void wrapUsesTheFirstWidthOnlyForTheFirstPiece() {
        assertEquals(List.of("one", "two two", "three"), ProblemRows.wrap("one two two three", 3, 7));
    }

    @Test
    void wrapKeepsShortTextOnOneRow() {
        assertEquals(List.of("short"), ProblemRows.wrap("short", 10, 10));
    }

    @Test
    void severityGlyphHasAnAsciiFaceAndANerdFace() {
        assertEquals("x", ProblemRows.glyph(Severity.ERROR, IconMode.ASCII));
        assertEquals("!", ProblemRows.glyph(Severity.WARNING, IconMode.ASCII));
        assertEquals("", ProblemRows.glyph(Severity.ERROR, IconMode.NERD));
        assertEquals("", ProblemRows.glyph(Severity.WARNING, IconMode.NERD));
    }

    @Test
    void aProblemRowCarriesSeverityLineColumnAndSource() {
        Diagnostic diagnostic = new Diagnostic(FILE, 3, 2, 3, 8, Severity.ERROR, "jdtls", "cannot find symbol");

        List<ProblemRows.Row> rows = ProblemRows.problem(4, diagnostic, true, 80, IconMode.ASCII, role -> Style.EMPTY);

        assertEquals(1, rows.size());
        assertEquals(4, rows.getFirst().problemIndex());
        assertEquals("> x ERROR  L4:C3  jdtls  cannot find symbol", text(rows.getFirst().line()));
    }

    @Test
    void aLongMessageWrapsUnderTheMessageColumn() {
        String message = "incompatible types: String cannot be converted to int in the overloaded call";
        Diagnostic diagnostic = new Diagnostic(FILE, 0, 0, 0, 1, Severity.ERROR, "jdtls", message);

        List<String> texts = ProblemRows.problem(0, diagnostic, false, 60, IconMode.ASCII, role -> Style.EMPTY)
                .stream().map(row -> text(row.line())).toList();

        assertTrue(texts.size() > 1, texts.toString());
        int messageColumn = texts.getFirst().indexOf("incompatible");
        assertEquals(25, messageColumn, texts.getFirst());
        for (String continuation : texts.subList(1, texts.size())) {
            assertEquals(messageColumn, continuation.length() - continuation.stripLeading().length(), continuation);
        }
        String joined = Stream.concat(Stream.of(texts.getFirst().substring(messageColumn)),
                texts.subList(1, texts.size()).stream().map(String::strip)).collect(Collectors.joining(" "));
        assertEquals(message, joined);
        texts.forEach(row -> assertTrue(row.length() <= 60, row));
    }

    @Test
    void aNarrowPaneMovesTheMessageBelowTheLocationAndKeepsItReadable() {
        String message = "missing return statement";
        Diagnostic diagnostic = new Diagnostic(FILE, 9, 4, 9, 5, Severity.WARNING, "javac", message);

        List<String> texts = ProblemRows.problem(2, diagnostic, false, 24, IconMode.ASCII, role -> Style.EMPTY)
                .stream().map(row -> text(row.line())).toList();

        assertEquals("  ! WARN   L10:C5  javac", texts.getFirst());
        assertEquals(message, texts.subList(1, texts.size()).stream().map(String::strip)
                .collect(Collectors.joining(" ")));
        texts.forEach(row -> assertTrue(row.length() <= 24, row));
    }

    @Test
    void stateLinesDoNotSelectAProblem() {
        List<ProblemRows.Row> rows = ProblemRows.plain("No problems in open files", 80, Style.EMPTY);

        assertEquals(List.of(-1), rows.stream().map(ProblemRows.Row::problemIndex).toList());
    }

    private static String text(Line line) {
        StringBuilder text = new StringBuilder();
        for (Span span : line.spans()) {
            text.append(span.content());
        }
        return text.toString();
    }
}
