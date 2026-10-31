package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Color.Rgb;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the downgrade of every required variable of both shipped themes against
 * the index the README palette table documents, and the search itself against
 * the range of the fixed palette. The pinned indices are what a reader of the
 * README sees, so a changed theme value or a changed distance fails here.
 */
class PaletteDowngradeTest {

    private static final Map<String, Integer> DARCULA_INDICES = Map.ofEntries(
            entry("panel", 237),
            entry("chrome-fg", 250),
            entry("chrome-fg-strong", 253),
            entry("muted", 243),
            entry("soft", 244),
            entry("selection-bg", 26),
            entry("selection-fg", 253),
            entry("tab-selected-bg", 60),
            entry("tab-selected-fg", 253),
            entry("border", 239),
            entry("focus-border", 60),
            entry("editor-border", 239),
            entry("editor-bg", 235),
            entry("editor-fg", 146),
            entry("editor-keyword", 173),
            entry("editor-string", 65),
            entry("editor-comment", 244),
            entry("editor-number", 67),
            entry("editor-constant", 103),
            entry("editor-selection", 24),
            entry("editor-sel-fg", 146));

    private static final Map<String, Integer> DARK_INDICES = Map.ofEntries(
            entry("panel", 236),
            entry("chrome-fg", 254),
            entry("chrome-fg-strong", 231),
            entry("muted", 243),
            entry("soft", 245),
            entry("selection-bg", 239),
            entry("selection-fg", 254),
            entry("tab-selected-bg", 69),
            entry("tab-selected-fg", 231),
            entry("border", 234),
            entry("focus-border", 69),
            entry("editor-border", 238),
            entry("editor-bg", 234),
            entry("editor-fg", 250),
            entry("editor-keyword", 173),
            entry("editor-string", 71),
            entry("editor-comment", 244),
            entry("editor-number", 37),
            entry("editor-constant", 175),
            entry("editor-selection", 24),
            entry("editor-sel-fg", 250));

    @Test
    void shippedThemesMapEveryRequiredVariableToThePinnedIndex() {
        ShellThemes authored = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        ShellThemes indexed = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null, ColorLevel.INDEXED_256);

        assertPinned(authored, "darcula", DARCULA_INDICES);
        assertPinned(authored, "dark", DARK_INDICES);
        assertIndexedText(indexed, "darcula", DARCULA_INDICES);
        assertIndexedText(indexed, "dark", DARK_INDICES);
    }

    @Test
    void documentedIndicesBelowTheTerminalPaletteAreRecomputed() {
        assertEquals(231, indexOf("#FFFFFF"),
                "white leaves the terminal palette entry 15 for the cube corner 231");
        assertEquals(69, indexOf("#3574F0"),
                "the New UI tab blue leaves the terminal palette entry 12 for 69");
        assertEquals(244, indexOf("#7A7E85"),
                "the New UI line comment leaves the terminal palette entry 8 for the grayscale ramp");
    }

    @Test
    void documentedKeywordIndexSurvivesTheDistance() {
        assertEquals(173, indexOf("#CC7832"),
                "the README documents 173 for the Darcula keyword and the squared distance reproduces it");
    }

    @Test
    void theTerminalPaletteIsNotACandidate() {
        assertEquals(16, PaletteDowngrade.nearestIndex(0, 0, 0),
                "black stays in the fixed cube instead of the terminal palette entry 0");
        assertEquals(231, PaletteDowngrade.nearestIndex(255, 255, 255),
                "white stays in the fixed cube instead of the terminal palette entry 15");
        assertEquals(240, PaletteDowngrade.nearestIndex(85, 85, 85),
                "the exact terminal palette gray 8 is not a candidate, the ramp entry 240 replaces it");
    }

    @Test
    void nearestReturnsAnIndexedColorOfTheCappedPalette() {
        assertEquals(Color.indexed(237), PaletteDowngrade.nearest(Color.hex("#3C3F41")),
                "the Darcula panel maps to the indexed color the stylesheet carries");
        assertEquals(Color.indexed(173), PaletteDowngrade.nearest(Color.hex("#CC7832")));
    }

    private static void assertPinned(ShellThemes themes, String theme, Map<String, Integer> pinned) {
        assertEquals(Set.copyOf(ShellThemes.REQUIRED_VARIABLES), pinned.keySet(),
                "the pin table covers exactly the required variables of '" + theme + "'");
        Map<String, String> variables = themes.variablesOf(theme);
        for (String name : List.copyOf(ShellThemes.REQUIRED_VARIABLES)) {
            assertEquals(pinned.get(name), indexOf(variables.get(name)),
                    "theme '" + theme + "' maps '" + name + "' to the pinned index");
        }
    }

    /**
     * The registered stylesheet carries the index as text, not the hex it was
     * computed from, so a reader of the sheet sees the same number.
     */
    private static void assertIndexedText(ShellThemes themes, String theme, Map<String, Integer> pinned) {
        Map<String, String> variables = themes.variablesOf(theme);
        for (String name : List.copyOf(ShellThemes.REQUIRED_VARIABLES)) {
            assertEquals("indexed(" + pinned.get(name) + ")", variables.get(name),
                    "theme '" + theme + "' must serve '" + name + "' as the rewritten index");
        }
    }

    private static int indexOf(String value) {
        Rgb rgb = Color.hex(value).toRgb();
        return PaletteDowngrade.nearestIndex(rgb.r(), rgb.g(), rgb.b());
    }
}
