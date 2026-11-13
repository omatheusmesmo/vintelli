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
                "#336699", "#ffffff", "#323232");
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
                "indexed(24)", "indexed(250)", "#323232");
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

    @Test
    void deprecatedRangeRendersStruckThroughInTheMutedColorAndItsNeighbourDoesNot() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);
        highlighter.setDeprecated(List.of(new EditorSyntaxHighlighter.DeprecatedRange(0, 4, 7)));
        TextArea textArea = TextArea.builder()
                .style(Style.EMPTY)
                .highlighter(highlighter, "java", SyntaxTheme.DEFAULTS)
                .build();
        Rect area = new Rect(0, 0, 20, 1);
        Buffer buffer = Buffer.empty(area);

        textArea.render(area, buffer, new TextAreaState("int old = next;"));

        for (int x = 4; x < 7; x++) {
            assertTrue(buffer.get(x, 0).style().addModifiers().contains(Modifier.CROSSED_OUT),
                    "the deprecated identifier is struck through at column " + x);
            assertEquals(Color.hex(palette.comment()), buffer.get(x, 0).style().fg().orElseThrow(),
                    "the deprecated identifier uses the muted editor color");
        }
        for (int x = 10; x < 14; x++) {
            assertFalse(buffer.get(x, 0).style().addModifiers().contains(Modifier.CROSSED_OUT),
                    "a non-deprecated identifier is not struck through at column " + x);
        }
    }

    @Test
    void deprecatedRangeKeepsTheDiagnosticTintAndTheSelectionReversal() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String code = "int old = 1;\n";
        highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        highlighter.setDiagnostics(List.of(
                new EditorSyntaxHighlighter.Diagnostic(0, EditorSyntaxHighlighter.Severity.WARNING)));
        highlighter.setDeprecated(List.of(new EditorSyntaxHighlighter.DeprecatedRange(0, 4, 7)));
        highlighter.setSelection(new SelectionCursor(0, 4, 0, 7));

        List<Line> lines = highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        Span struck = lines.get(0).spans().stream()
                .filter(span -> span.style().addModifiers().contains(Modifier.CROSSED_OUT))
                .findFirst().orElseThrow();
        assertEquals("old", struck.content());
        assertTrue(struck.style().addModifiers().contains(Modifier.REVERSED), "the selection reversal stays");
        assertTrue(struck.style().addModifiers().contains(Modifier.UNDERLINED), "the diagnostic underline stays");
        assertEquals(Color.ansi(AnsiColor.YELLOW), struck.style().bg().orElse(null),
                "the diagnostic background stays");
    }

    @Test
    void aNonDeprecatedDiagnosticIsNotStruckThrough() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        String code = "int old = 1;\n";
        highlighter.setDiagnostics(List.of(
                new EditorSyntaxHighlighter.Diagnostic(0, EditorSyntaxHighlighter.Severity.ERROR)));

        List<Line> lines = highlighter.highlight(code, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        for (Span span : lines.get(0).spans()) {
            assertFalse(span.style().addModifiers().contains(Modifier.CROSSED_OUT),
                    "a diagnostic without the deprecated flag never strikes text through");
        }
    }

    @Test
    void docCommentOpenerKeepsFollowingLinesAsComment() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\nint value = 1;\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        Span body = lines.get(1).spans().stream()
                .filter(span -> span.content().contains("int"))
                .findFirst().orElseThrow();
        assertEquals(Color.hex(palette.comment()), body.style().fg().orElseThrow(),
                "the body of an open doc comment stays a comment on the next line");
    }

    @Test
    void emptyDocCommentOpenerIsARegularComment() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**/\nint value = 1;\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        Span body = lines.get(1).spans().stream()
                .filter(span -> span.content().contains("int"))
                .findFirst().orElseThrow();
        assertEquals(Color.hex(palette.keyword()), body.style().fg().orElseThrow(),
                "'/**/' is a closed regular comment and must not swallow the next line");
    }

    @Test
    void changingABlockCommentIntoADocCommentRetokenizesTheBody() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        highlighter.highlight("/*\n * text\n */\n", "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        highlighter.highlight("/**\n * text\n */\n", "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(3, highlighter.lastTokenizedLines(),
                "the doc flag is part of the lexer state, so an opener change re-tokenizes the body");
    }

    @Test
    void docCommentBlockTagIsAnAnnotationSpanAndFlowTextStaysAComment() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * @param value the value\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(3, spans.size(), "leading text, the tag and the rest of the line form three spans");
        assertEquals(" * ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow(),
                "the doc comment body is flow text up to the tag");
        assertEquals("@param", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow(),
                "a javadoc tag is annotated, which renders in the keyword color");
        assertEquals(" value the value", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow(),
                "the text after the tag is flow text again");
    }

    @Test
    void docCommentTagOnTheOpenerLineIsAnAnnotationSpan() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/** @deprecated use the new API */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(0).spans();
        assertEquals(3, spans.size(), "the single line doc comment splits into opener, tag and rest");
        assertEquals("/** ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        assertEquals("@deprecated", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow());
        assertEquals(" use the new API */", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow());
    }

    @Test
    void atSignInsideFlowTextIsNotATag() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * mail me at a@b.de\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(1, spans.size(), "an '@' preceded by a word character is not a tag");
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
    }

    @Test
    void atSignWithoutATagNameStaysFlowText() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * @\n */\n", "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(1, spans.size(), "an '@' without a name must not break the word scan");
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
    }

    @Test
    void blockAndLineCommentsDoNotHighlightTags() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/* @param value */\n// @param value\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(1, lines.get(0).spans().size(), "a regular block comment holds no tags");
        assertEquals(Color.hex(palette.comment()), lines.get(0).spans().get(0).style().fg().orElseThrow());
        assertEquals(1, lines.get(1).spans().size(), "a line comment holds no tags");
        assertEquals(Color.hex(palette.comment()), lines.get(1).spans().get(0).style().fg().orElseThrow());
    }

    @Test
    void editingADocTagRetokenizesOnlyThatLine() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);
        highlighter.highlight("/**\n * @param a the first\n * @param b the second\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Line> lines = highlighter.highlight("/**\n * @param a the first\n * @param b the changed\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(1, highlighter.lastTokenizedLines(), "only the edited doc line is re-tokenized");
        List<Span> spans = lines.get(2).spans();
        assertEquals(3, spans.size());
        assertEquals("@param", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow(),
                "the tag stays highlighted after a warm cache pass");
    }

    @Test
    void docCommentInlineTagIsAnAnnotationSpanAndFlowTextStaysAComment() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * see {@link Foo} here\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(3, spans.size(), "leading text, the inline tag and the rest form three spans");
        assertEquals(" * see ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow(),
                "the text before the inline tag stays a comment");
        assertEquals("{@link Foo}", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow(),
                "an inline tag is an annotation span, including its label");
        assertEquals(" here", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow(),
                "the text after the inline tag stays a comment");
    }

    @Test
    void everyInlineTagOnALineGetsItsOwnAnnotationSpan() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * {@inheritDoc} and {@value}\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(4, spans.size(), "two inline tags split the line into four spans");
        assertEquals(" * ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        assertEquals("{@inheritDoc}", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow());
        assertEquals(" and ", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow());
        assertEquals("{@value}", spans.get(3).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(3).style().fg().orElseThrow());
    }

    @Test
    void unclosedInlineTagKeepsTheLineScanSafe() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * see {@link Foo\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(3, spans.size(), "an inline tag without a closing brace must not break the line");
        assertEquals(" * see ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        assertEquals("{@link", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow(),
                "the tag name is still highlighted without a closing brace");
        assertEquals(" Foo", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow(),
                "the rest of the line stays flow text");
        assertEquals(1, lines.get(2).spans().size(), "the closing line stays one comment span");
        assertEquals(Color.hex(palette.comment()), lines.get(2).spans().get(0).style().fg().orElseThrow());
    }

    @Test
    void inlineTagIsScopedToOneLine() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * {@link Foo\n * }\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(3, spans.size());
        assertEquals("{@link", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow());
        List<Span> closing = lines.get(2).spans();
        assertEquals(1, closing.size(), "a brace on the next line is plain flow text");
        assertEquals(Color.hex(palette.comment()), closing.get(0).style().fg().orElseThrow(),
                "an inline tag never spans lines");
    }

    @Test
    void blockAndLineCommentsDoNotHighlightInlineTags() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/*\n * see {@link Foo}\n */\n// {@link Foo}\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(1, lines.get(1).spans().size(), "a regular block comment holds no inline tags");
        assertEquals(Color.hex(palette.comment()), lines.get(1).spans().get(0).style().fg().orElseThrow());
        assertEquals(1, lines.get(3).spans().size(), "a line comment holds no inline tags");
        assertEquals(Color.hex(palette.comment()), lines.get(3).spans().get(0).style().fg().orElseThrow());
    }

    @Test
    void blockTagAndInlineTagCoexistInOneLine() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * @param x the {@link Foo} value\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(5, spans.size(), "block tag and inline tag split the line into five spans");
        assertEquals(" * ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        assertEquals("@param", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow());
        assertEquals(" x the ", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow());
        assertEquals("{@link Foo}", spans.get(3).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(3).style().fg().orElseThrow(),
                "the inline tag after a block tag keeps its own span");
        assertEquals(" value", spans.get(4).content());
        assertEquals(Color.hex(palette.comment()), spans.get(4).style().fg().orElseThrow());
    }

    @Test
    void docCommentHtmlTagIsATagSpanAndFlowTextStaysAComment() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * <p>text</p>\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(4, spans.size(), "two HTML tags split the line into four spans");
        assertEquals(" * ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        assertEquals("<p>", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow(),
                "an HTML tag is a TAG span and renders in the palette keyword colour");
        assertEquals("text", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow(),
                "the text between HTML tags stays a comment");
        assertEquals("</p>", spans.get(3).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(3).style().fg().orElseThrow(),
                "the closing HTML tag keeps its own span");
    }

    @Test
    void htmlTagWithAttributesIsASingleTagSpan() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * <a href=\"x\">y</a>\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(4, spans.size(), "the attributes stay inside the tag span");
        assertEquals(" * ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        assertEquals("<a href=\"x\">", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow(),
                "a tag with attributes is one span up to its closing angle bracket");
        assertEquals("y", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow());
        assertEquals("</a>", spans.get(3).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(3).style().fg().orElseThrow());
    }

    @Test
    void htmlTagOnTheOpenerLineIsATagSpan() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/** <p>text</p> */\n", "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(0).spans();
        assertEquals(5, spans.size(), "the opener line is scanned for HTML tags as well");
        assertEquals("/** ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        assertEquals("<p>", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow());
        assertEquals("text", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow());
        assertEquals("</p>", spans.get(3).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(3).style().fg().orElseThrow());
        assertEquals(" */", spans.get(4).content());
        assertEquals(Color.hex(palette.comment()), spans.get(4).style().fg().orElseThrow());
    }

    @Test
    void htmlTagIsScopedToOneLine() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * <p\n * text</p>\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(1, spans.size(), "a tag without a closing angle bracket must not break the line");
        assertEquals(" * <p", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow(),
                "an unfinished tag stays flow text");
        List<Span> next = lines.get(2).spans();
        assertEquals(2, next.size(), "a tag opening on the previous line does not carry over");
        assertEquals(" * text", next.get(0).content());
        assertEquals(Color.hex(palette.comment()), next.get(0).style().fg().orElseThrow());
        assertEquals("</p>", next.get(1).content());
        assertEquals(Color.hex(palette.keyword()), next.get(1).style().fg().orElseThrow());
    }

    @Test
    void comparisonSignsAndLoneAngleBracketsStayFlowText() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * a < b > c\n * <\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(1, spans.size(), "an angle bracket followed by a blank is a comparison sign");
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        List<Span> lone = lines.get(2).spans();
        assertEquals(1, lone.size(), "a trailing angle bracket must not break the scan");
        assertEquals(Color.hex(palette.comment()), lone.get(0).style().fg().orElseThrow());
    }

    @Test
    void blockAndLineCommentsDoNotHighlightHtmlTags() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/*\n * <p>text</p>\n */\n// <p>text</p>\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(1, lines.get(1).spans().size(), "a regular block comment holds no HTML tags");
        assertEquals(Color.hex(palette.comment()), lines.get(1).spans().get(0).style().fg().orElseThrow());
        assertEquals(1, lines.get(3).spans().size(), "a line comment holds no HTML tags");
        assertEquals(Color.hex(palette.comment()), lines.get(3).spans().get(0).style().fg().orElseThrow());
    }

    @Test
    void htmlTagCoexistsWithBlockAndInlineTags() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * @param x the {@link Foo} <p>see</p>\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(1).spans();
        assertEquals(8, spans.size(), "block tag, inline tag and HTML tags split the line into eight spans");
        assertEquals(" * ", spans.get(0).content());
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
        assertEquals("@param", spans.get(1).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(1).style().fg().orElseThrow());
        assertEquals(" x the ", spans.get(2).content());
        assertEquals(Color.hex(palette.comment()), spans.get(2).style().fg().orElseThrow());
        assertEquals("{@link Foo}", spans.get(3).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(3).style().fg().orElseThrow());
        assertEquals(" ", spans.get(4).content());
        assertEquals(Color.hex(palette.comment()), spans.get(4).style().fg().orElseThrow());
        assertEquals("<p>", spans.get(5).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(5).style().fg().orElseThrow(),
                "the HTML tag after an inline tag keeps its own span");
        assertEquals("see", spans.get(6).content());
        assertEquals(Color.hex(palette.comment()), spans.get(6).style().fg().orElseThrow());
        assertEquals("</p>", spans.get(7).content());
        assertEquals(Color.hex(palette.keyword()), spans.get(7).style().fg().orElseThrow());
    }

    @Test
    void stringLiteralWithJavadocLikeContentStaysUnhighlighted() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("String s = \"/** @param x <p> {@link Foo}\";\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> spans = lines.get(0).spans();
        Span literal = spans.stream()
                .filter(span -> span.content().contains("@param"))
                .findFirst().orElseThrow();
        assertEquals(Color.hex(palette.string()), literal.style().fg().orElseThrow(),
                "a doc comment opener inside a string literal stays a string, not a comment");
        assertFalse(spans.stream().anyMatch(span -> span.style().fg().orElseThrow().equals(Color.hex(palette.keyword()))),
                "a string literal must not expose javadoc or HTML tag spans");
    }

    @Test
    void textBlockWithJavadocLikeContentStaysUnhighlighted() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("String s = \"\"\"\n/**\n * @param x\n */\n\"\"\";\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        Span body = lines.get(2).spans().stream()
                .filter(span -> span.content().contains("@param"))
                .findFirst().orElseThrow();
        assertEquals(Color.hex(palette.string()), body.style().fg().orElseThrow(),
                "the body of a text block stays a string, not a comment");
        for (Line line : lines) {
            assertFalse(line.spans().stream()
                    .anyMatch(span -> span.style().fg().orElseThrow().equals(Color.hex(palette.keyword()))),
                    "a text block must not expose javadoc or HTML tag spans");
        }
    }

    @Test
    void changingADocCommentBackIntoABlockCommentRetokenizesTheBody() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);
        highlighter.highlight("/**\n * @param x\n */\n", "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Line> lines = highlighter.highlight("/*\n * @param x\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(3, highlighter.lastTokenizedLines(),
                "dropping the second star clears the doc flag, so the cached body re-tokenizes");
        List<Span> spans = lines.get(1).spans();
        assertEquals(1, spans.size(), "a regular block comment no longer highlights the tag");
        assertEquals(Color.hex(palette.comment()), spans.get(0).style().fg().orElseThrow());
    }

    @Test
    void insertingALineInsideADocCommentReusesTheUnchangedSuffix() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);
        String original = "/**\n * @param a\n */\nclass C {\n}\n";
        highlighter.highlight(original, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Line> lines = highlighter.highlight(original.replace(" * @param a\n", " * @param a\n * @param b\n"),
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(1, highlighter.lastTokenizedLines(),
                "only the inserted doc line is tokenized, the cached doc suffix is reused");
        List<Span> inserted = lines.get(2).spans();
        assertEquals(3, inserted.size());
        assertEquals("@param", inserted.get(1).content());
        assertEquals(Color.hex(palette.keyword()), inserted.get(1).style().fg().orElseThrow(),
                "the tag on the inserted line is highlighted");
        assertEquals(1, lines.get(3).spans().size(), "the cached closing line stays one comment span");
        assertEquals(Color.hex(palette.comment()), lines.get(3).spans().get(0).style().fg().orElseThrow());
    }

    @Test
    void editingALineCommentRetokenizesOnlyThatLine() {
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        ShellEditorPalette palette = new ShellEditorPalette(
                "#101010", "#aabbcc", "#dd8844", "#44aa66", "#778899", "#ccaa55", "#bb66cc",
                "#336699", "#ffffff", "#323232");
        highlighter.editorPalette(palette);
        highlighter.highlight("// first\nint x = 1;\n// third\n", "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Line> lines = highlighter.highlight("// changed\nint x = 1;\n// third\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        assertEquals(1, highlighter.lastTokenizedLines(), "only the edited line comment is re-tokenized");
        List<Span> reused = lines.get(2).spans();
        assertEquals(1, reused.size(), "a line comment holds no tags after a warm cache pass");
        assertEquals(Color.hex(palette.comment()), reused.get(0).style().fg().orElseThrow());
    }
}
