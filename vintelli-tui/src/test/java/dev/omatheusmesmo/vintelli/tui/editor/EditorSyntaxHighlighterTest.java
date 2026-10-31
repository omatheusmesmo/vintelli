package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.syntax.SyntaxTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
