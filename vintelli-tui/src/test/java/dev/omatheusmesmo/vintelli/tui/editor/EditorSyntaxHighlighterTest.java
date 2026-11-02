package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.AnsiColor;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.syntax.SyntaxTheme;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSyntaxHighlighterTest {
    @Test
    void retokenizesOnlyAnEditedJavaLine() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String original = "class Sample {\n    int value = 1;\n}\n";
        highlighter.highlight(original, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        highlighter.highlight(original.replace("value = 1", "value = 2"), "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(1, highlighter.lastTokenizedLines());
    }

    @Test
    void insertingALineReusesTheUnchangedSuffix() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String original = "class Sample {\n    int value = 1;\n    int next = 2;\n}\n";
        highlighter.highlight(original, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        highlighter.highlight(original.replace("    int next", "    int middle = 3;\n    int next"),
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(1, highlighter.lastTokenizedLines());
    }

    @Test
    void renderedJavaKeywordsUseTheActiveEditorPalette() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff");
        highlighter.editorPalette(palette);
        TextArea textArea = TextArea.builder()
                .style(Style.EMPTY)
                .highlighter(highlighter, "java", SyntaxTheme.DEFAULTS)
                .build();
        Rect area = new Rect(0, 0, 12, 1);
        Buffer buffer = Buffer.empty(area);

        textArea.render(area, buffer, new TextAreaState("public class"));

        assertEquals(Color.hex(palette.keyword()), buffer.get(0, 0).style().fg().orElseThrow());
        assertEquals(Color.hex(palette.keyword()), buffer.get(1, 0).style().fg().orElseThrow());
    }

    @Test
    void renderedJavaKeywordsUseAnIndexedEditorPaletteAsWell() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "indexed(234)", "indexed(250)", "indexed(173)", "indexed(71)", "indexed(244)", "indexed(37)", "indexed(175)",
                "indexed(24)", "indexed(250)");
        highlighter.editorPalette(palette);
        TextArea textArea = TextArea.builder()
                .style(Style.EMPTY)
                .highlighter(highlighter, "java", SyntaxTheme.DEFAULTS)
                .build();
        Rect area = new Rect(0, 0, 12, 1);
        Buffer buffer = Buffer.empty(area);

        textArea.render(area, buffer, new TextAreaState("public class"));

        assertEquals(Color.indexed(173), buffer.get(0, 0).style().fg().orElseThrow(),
                "an indexed editor palette renders indexed keywords instead of failing on the hex parser");
        assertEquals(Color.indexed(173), buffer.get(1, 0).style().fg().orElseThrow());
    }

    @Test
    void diagnosticRowsHaveSeverityColoredUnderline_error() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String code = "int x = 1;\n";
        highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        highlighter.setDiagnostics(List.of(
                new EditorSyntaxHighlighter.Diagnostic(0, EditorSyntaxHighlighter.Severity.ERROR)));
        List<Line> lines = highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        Line diagnosticLine = lines.get(0);
        Color expectedBg = Color.ansi(AnsiColor.RED);
        Color expectedUnderline = Color.ansi(AnsiColor.BRIGHT_RED);
        assertFalse(diagnosticLine.spans().isEmpty(), "diagnostic line must have spans");
        for (Span span : diagnosticLine.spans()) {
            assertEquals(expectedBg, span.style().bg().orElse(null),
                    "ERROR diagnostic row background must be red");
            assertTrue(span.style().addModifiers().contains(Modifier.UNDERLINED),
                    "ERROR diagnostic row must be underlined");
            assertEquals(expectedUnderline, span.style().underlineColor().orElse(null),
                    "ERROR diagnostic underline color must be bright red");
        }
    }

    @Test
    void diagnosticRowsHaveSeverityColoredUnderline_warning() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String code = "int x = 1;\n";
        highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        highlighter.setDiagnostics(List.of(
                new EditorSyntaxHighlighter.Diagnostic(0, EditorSyntaxHighlighter.Severity.WARNING)));
        List<Line> lines = highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        Line diagnosticLine = lines.get(0);
        Color expectedBg = Color.ansi(AnsiColor.YELLOW);
        Color expectedUnderline = Color.ansi(AnsiColor.BRIGHT_YELLOW);
        assertFalse(diagnosticLine.spans().isEmpty(), "diagnostic line must have spans");
        for (Span span : diagnosticLine.spans()) {
            assertEquals(expectedBg, span.style().bg().orElse(null),
                    "WARNING diagnostic row background must be yellow");
            assertTrue(span.style().addModifiers().contains(Modifier.UNDERLINED),
                    "WARNING diagnostic row must be underlined");
            assertEquals(expectedUnderline, span.style().underlineColor().orElse(null),
                    "WARNING diagnostic underline color must be bright yellow");
        }
    }

    @Test
    void diagnosticRowsHaveSeverityColoredUnderline_info() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String code = "int x = 1;\n";
        highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        highlighter.setDiagnostics(List.of(
                new EditorSyntaxHighlighter.Diagnostic(0, EditorSyntaxHighlighter.Severity.INFO)));
        List<Line> lines = highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        Line diagnosticLine = lines.get(0);
        Color expectedBg = Color.ansi(AnsiColor.BLUE);
        Color expectedUnderline = Color.ansi(AnsiColor.CYAN);
        assertFalse(diagnosticLine.spans().isEmpty(), "diagnostic line must have spans");
        for (Span span : diagnosticLine.spans()) {
            assertEquals(expectedBg, span.style().bg().orElse(null),
                    "INFO diagnostic row background must be blue");
            assertTrue(span.style().addModifiers().contains(Modifier.UNDERLINED),
                    "INFO diagnostic row must be underlined");
            assertEquals(expectedUnderline, span.style().underlineColor().orElse(null),
                    "INFO diagnostic underline color must be cyan");
        }
    }

    @Test
    void rowsWithoutDiagnosticAreNotUnderlined() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String code = "int x = 1;\n";
        List<Line> lines = highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertFalse(lines.get(0).spans().isEmpty());
        for (Span span : lines.get(0).spans()) {
            assertFalse(span.style().addModifiers().contains(Modifier.UNDERLINED),
                    "a row without a diagnostic must not be underlined");
        }
    }

    @Test
    void selectionOnDiagnosticRowKeepsReversalAndUnderline() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String code = "int x = 1;\n";
        highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        highlighter.setDiagnostics(List.of(
                new EditorSyntaxHighlighter.Diagnostic(0, EditorSyntaxHighlighter.Severity.ERROR)));
        highlighter.setSelection(new SelectionCursor(0, 0, 0, 3));

        List<Line> lines = highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        boolean anyReversed = false;
        for (Span span : lines.get(0).spans()) {
            assertTrue(span.style().addModifiers().contains(Modifier.UNDERLINED),
                    "every span on a diagnostic row stays underlined, selected or not");
            anyReversed |= span.style().addModifiers().contains(Modifier.REVERSED);
        }
        assertTrue(anyReversed, "the selected span keeps its reversed style");
    }
}
