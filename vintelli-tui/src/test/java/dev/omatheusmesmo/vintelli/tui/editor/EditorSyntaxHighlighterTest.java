package dev.omatheusmesmo.vintelli.tui.editor;

import dev.tamboui.style.Style;
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
}
