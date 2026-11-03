package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.syntax.SyntaxTheme;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the color the tags of a doc comment render in stays legible in
 * both shipped themes. Both tag kinds are served in the palette keyword color
 * (the highlighter maps {@code TokenType.ANNOTATION} for Javadoc tags and
 * {@code TokenType.TAG} for HTML tags onto it) and sit inside the comment
 * prose, so a tag has exactly two
 * neighbors to hold up against: the prose it annotates and the editor
 * background.
 *
 * <p>The measure is the WCAG relative luminance contrast ratio, computed on
 * the rendered spans instead of on the palette record, so a tag that silently
 * falls back to the prose color or to a library default fails here as well.
 * Hue is deliberately ignored: the part of the separation that survives
 * without color is the part a reader with a color vision deficiency can rely
 * on.
 *
 * <p>No new palette slot was added. Measured over both themes the keyword
 * color already clears both floors: the tag is brighter than the prose and it
 * clears the WCAG AA large text floor against the editor background. The
 * authored values are pinned against the IntelliJ platform sources in
 * {@code ThemePaletteSourceTest}, this test pins that they are legible.
 */
class EditorPaletteContrastTest {

    /**
     * A tag has to stand out from the prose. Both shipped themes separate
     * distinct editor roles from the comment color by as little as 1.02
     * (Darcula string, 1.018), so this floor asks for headroom above the
     * family's weakest separation without demanding a standard the themes do
     * not follow. Measured: Darcula 1.19, Dark 1.50.
     */
    private static final double MIN_TAG_TO_PROSE = 1.1;

    /**
     * The WCAG AA contrast minimum for large text and graphical objects. The
     * comment prose of both themes clears it (Darcula 3.59, Dark 4.04) and the
     * tag must not sink below the band its own prose is read in. Measured:
     * Darcula 4.25, Dark 6.08.
     */
    private static final double MIN_TAG_TO_BACKGROUND = 3.0;

    private static final List<String> SHIPPED_THEMES = List.of("darcula", "dark");

    @Test
    void bothTagKindsRenderInTheSamePaletteColor() {
        for (String theme : SHIPPED_THEMES) {
            Rendered rendered = rendered(theme);

            assertEquals(rendered.javadocTag(), rendered.htmlTag(),
                    "theme '" + theme + "' must paint the Javadoc tag and the HTML tag in one color");
            assertEquals(Color.hex(palette(theme).keyword()), rendered.javadocTag(),
                    "theme '" + theme + "' must paint the Javadoc tag in the palette keyword color");
        }
    }

    @Test
    void tagColorsStandOutFromTheCommentProse() {
        for (String theme : SHIPPED_THEMES) {
            Rendered rendered = rendered(theme);
            double ratio = contrast(rendered.javadocTag(), rendered.prose());

            assertTrue(ratio >= MIN_TAG_TO_PROSE,
                    "the tags of theme '" + theme + "' must stand out from the prose: "
                            + rendered.javadocTag() + " vs " + rendered.prose() + " is " + ratio);
            assertTrue(luminance(rendered.javadocTag()) > luminance(rendered.prose()),
                    "the tags of theme '" + theme + "' must be the brighter of the two");
        }
    }

    @Test
    void tagColorsStayReadableOnTheEditorBackground() {
        for (String theme : SHIPPED_THEMES) {
            Rendered rendered = rendered(theme);
            double ratio = contrast(rendered.javadocTag(), rendered.background());

            assertTrue(ratio >= MIN_TAG_TO_BACKGROUND,
                    "the tags of theme '" + theme + "' must stay readable on the editor background: "
                            + rendered.javadocTag() + " vs " + rendered.background() + " is " + ratio);
        }
    }

    /**
     * Runs a doc comment through the real highlighter with the palette of a
     * shipped theme: the tag spans and the prose span are read off the rendered
     * lines, not off the palette record, so the mapping the editor uses is part
     * of what is measured. The comment carries one tag of each kind, so a
     * regression in either mapping shows up here.
     */
    private static Rendered rendered(String theme) {
        ShellEditorPalette palette = palette(theme);
        EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
        highlighter.editorPalette(palette);

        List<Line> lines = highlighter.highlight("/**\n * @param x the value\n * <p>text</p>\n */\n",
                "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        List<Span> javadocLine = lines.get(1).spans();
        List<Span> htmlLine = lines.get(2).spans();
        return new Rendered(
                foregroundOf(javadocLine, "@param"),
                foregroundOf(htmlLine, "<p>"),
                foregroundOf(javadocLine, " x the value"),
                Color.hex(palette.background()));
    }

    private static ShellEditorPalette palette(String theme) {
        return new ShellThemes(theme, (Path) null).editorPalette();
    }

    private static Color foregroundOf(List<Span> spans, String content) {
        return spans.stream()
                .filter(span -> span.content().equals(content))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the doc comment line no longer carries the span '" + content + "': " + contentsOf(spans)))
                .style().fg().orElseThrow();
    }

    private static List<String> contentsOf(List<Span> spans) {
        return spans.stream().map(Span::content).toList();
    }

    /**
     * WCAG contrast ratio between two colors: the relative luminance of the
     * brighter color plus 0.05 over the darker one plus 0.05, so a ratio of
     * 1.0 means the two colors are indistinguishable in luminance.
     */
    private static double contrast(Color first, Color second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /**
     * WCAG relative luminance: the sRGB channels linearized and weighted the
     * way the WCAG definition weights them.
     */
    private static double luminance(Color color) {
        Color.Rgb rgb = color.toRgb();
        return 0.2126 * linearize(rgb.r()) + 0.7152 * linearize(rgb.g()) + 0.0722 * linearize(rgb.b());
    }

    private static double linearize(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private record Rendered(Color javadocTag, Color htmlTag, Color prose, Color background) {
    }
}
