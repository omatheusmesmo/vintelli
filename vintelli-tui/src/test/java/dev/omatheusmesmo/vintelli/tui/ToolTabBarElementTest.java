package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import org.junit.jupiter.api.Test;

class ToolTabBarElementTest {

    private static final List<IconMode> FACES = List.of(IconMode.NERD, IconMode.ASCII);
    private static final List<FakeToolWindow> WINDOWS = List.of(
            window("alpha", "Alpha", '4', ""),
            window("bravo", "Bravo", '6', ""),
            window("charlie", "Charlie", '7', ""),
            window("delta", "Delta", '8', ""));
    private static final int LOWEST_WIDTH = 5;
    private static final int HIGHEST_WIDTH = 40;

    @Test
    void everyTabIsDrawnWholeWhenTheRowHoldsAllOfThemInBothFaces() {
        for (IconMode mode : FACES) {
            List<String> rows = render(bar(mode, 1), 40);

            assertEquals(face(mode, " 4 Alpha  6 Bravo  7 Charlie  8 Delta"), rows.get(0).stripTrailing(), mode.toString());
            assertEquals(face(mode, "          -------"), rows.get(1).stripTrailing(), mode.toString());
        }
    }

    @Test
    void theOtherTabsShortenToTheirMarkersBeforeAnyTabIsHidden() {
        for (IconMode mode : FACES) {
            List<String> rows = render(bar(mode, 1), 20);

            assertEquals(face(mode, " 4  6 Bravo  7  8"), rows.get(0).stripTrailing(),
                    "the selected tab keeps its title and the row holds every tab, so no mark is drawn: " + mode);
            assertEquals(face(mode, "    -------"), rows.get(1).stripTrailing(), mode.toString());
        }
    }

    @Test
    void theSelectedTabKeepsItsTitleWhenItFitsBesideTheMarkersOfTheOthers() {
        for (IconMode mode : FACES) {
            List<String> rows = render(bar(mode, 3), 20);

            assertEquals(face(mode, " 4  6  7  8 Delta"), rows.get(0).stripTrailing(), mode.toString());
            assertEquals(face(mode, "          -------"), rows.get(1).stripTrailing(), mode.toString());
        }
    }

    @Test
    void aRowTooNarrowForAllTabsScrollsToTheSelectedTabAndMarksTheTabsHidden() {
        for (IconMode mode : FACES) {
            List<String> middle = render(bar(mode, 1), 12);
            assertEquals(face(mode, "< 6 Bravo  >"), middle.get(0), "tabs hidden on both sides: " + mode);
            assertEquals(face(mode, "  -------"), middle.get(1).stripTrailing(), mode.toString());

            List<String> last = render(bar(mode, 3), 12);
            assertEquals(face(mode, "< 7  8 Delta"), last.get(0), "the last tab is whole with the left mark: " + mode);
            assertEquals(face(mode, "     -------"), last.get(1).stripTrailing(), mode.toString());
        }
    }

    @Test
    void theSelectedTabShrinksToItsMarkerWhenItsTitleCannotBeDrawnWhole() {
        for (IconMode mode : FACES) {
            List<String> first = render(bar(mode, 1), 8);
            assertEquals(face(mode, " 4  6  >"), first.get(0), "the selected marker is whole and the right mark shows: " + mode);
            assertEquals(face(mode, "    -"), first.get(1).stripTrailing(), mode.toString());

            List<String> charlie = render(bar(mode, 2), 8);
            assertEquals(face(mode, "< 6  7 >"), charlie.get(0), mode.toString());
            assertEquals(face(mode, "     -"), charlie.get(1).stripTrailing(), mode.toString());

            List<String> narrow = render(bar(mode, 1), 5);
            assertEquals(face(mode, "< 6 >"), narrow.get(0), "the selected marker is still drawn at five columns: " + mode);
        }
    }

    @Test
    void theOverflowMarksAppearExactlyWhenTabsAreHidden() {
        for (IconMode mode : FACES) {
            String left = face(mode, "<");
            String right = face(mode, ">");
            for (int selected = 0; selected < WINDOWS.size(); selected++) {
                for (int width = LOWEST_WIDTH; width <= HIGHEST_WIDTH; width++) {
                    ToolTabBarElement bar = bar(mode, selected);
                    String row = render(bar, width).get(0);
                    Set<Integer> drawn = drawnTabs(bar, width);
                    String context = mode + " selected " + selected + " width " + width + ": " + row;

                    assertTrue(drawn.contains(selected), "the selected tab is drawn: " + context);
                    boolean hiddenBefore = drawn.stream().min(Integer::compare).orElseThrow() > 0;
                    boolean hiddenAfter = drawn.stream().max(Integer::compare).orElseThrow() < WINDOWS.size() - 1;
                    assertEquals(hiddenBefore, row.startsWith(left),
                            "the left mark shows exactly when tabs are hidden on the left: " + context);
                    assertEquals(hiddenAfter, row.endsWith(right),
                            "the right mark shows exactly when tabs are hidden on the right: " + context);
                }
            }
        }
    }

    @Test
    void theSelectedTabIsWholeOrMarkerOnlyAndTheOthersAreMarkerOnlyUntilEveryTabFits() {
        for (IconMode mode : FACES) {
            for (int selected = 0; selected < WINDOWS.size(); selected++) {
                for (int width = LOWEST_WIDTH; width <= HIGHEST_WIDTH; width++) {
                    ToolTabBarElement bar = bar(mode, selected);
                    render(bar, width);
                    String context = mode + " selected " + selected + " width " + width;

                    for (int index : drawnTabs(bar, width)) {
                        int drawn = slotWidth(bar, width, index);
                        int marker = 1;
                        int whole = labelWidth(WINDOWS.get(index));
                        if (index == selected) {
                            assertTrue(drawn == marker || drawn == whole, "the selected tab is never cut: " + context);
                        } else if (width < wholeRowWidth()) {
                            assertEquals(marker, drawn, "a tab that is not selected shrinks to its marker: " + context);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aClickOnAMarkSelectsNoTabAndAClickOnAWholeTabSelectsIt() {
        ToolTabBarElement bar = bar(IconMode.ASCII, 1);
        render(bar, 12);

        assertEquals(-1, bar.tabAt(0, 0), "the left mark is no tab");
        assertEquals(-1, bar.tabAt(11, 0), "the right mark is no tab");
        assertEquals(-1, bar.tabAt(9, 0), "a gap between tabs is no tab");
        assertEquals(1, bar.tabAt(2, 1), "the underline row under a tab answers a click on that tab");
        assertEquals(1, bar.tabAt(2, 0), "a click on the selected tab selects it");
        assertEquals(1, bar.tabAt(8, 0), "a click on the end of the selected tab selects it");
    }

    @Test
    void theAsciiRowDrawsOnlyAsciiGlyphs() {
        List<String> rows = render(bar(IconMode.ASCII, 1), 12);

        assertTrue(rows.get(0).chars().allMatch(character -> character < 128), rows.get(0));
        assertTrue(rows.get(1).chars().allMatch(character -> character < 128), rows.get(1));
    }

    private static int labelWidth(FakeToolWindow window) {
        return 1 + 1 + window.title().length();
    }

    private static int wholeRowWidth() {
        int gaps = 2 * (WINDOWS.size() - 1);
        return 1 + gaps + WINDOWS.stream().mapToInt(ToolTabBarElementTest::labelWidth).sum();
    }

    private static Set<Integer> drawnTabs(ToolTabBarElement bar, int width) {
        Set<Integer> drawn = new TreeSet<>();
        for (int x = 0; x < width; x++) {
            int index = bar.tabAt(x, 0);
            if (index >= 0) {
                drawn.add(index);
            }
        }
        return drawn;
    }

    private static int slotWidth(ToolTabBarElement bar, int width, int index) {
        int columns = 0;
        for (int x = 0; x < width; x++) {
            if (bar.tabAt(x, 0) == index) {
                columns++;
            }
        }
        return columns;
    }

    private static FakeToolWindow window(String id, String title, char shortcut, String icon) {
        return new FakeToolWindow(id, title, shortcut, 0, "", false, ToolWindow.Anchor.RIGHT) {
            @Override
            public String icon() {
                return icon;
            }
        };
    }

    private static ToolTabBarElement bar(IconMode mode, int selected) {
        List<ToolTabBarElement.Label> labels = new ArrayList<>();
        for (FakeToolWindow window : WINDOWS) {
            String marker = ToolStripes.nerdIcon(window, mode).orElseGet(() -> String.valueOf(window.shortcut()));
            labels.add(new ToolTabBarElement.Label(marker, window.title()));
        }
        return new ToolTabBarElement("tool-tabs-right", labels, selected, ToolTabBarElement.Glyphs.forMode(mode));
    }

    /** The ASCII layout written with the face's own marker and glyph characters. */
    private static String face(IconMode mode, String ascii) {
        if (mode == IconMode.ASCII) {
            return ascii;
        }
        return ascii.replace("4", "").replace("6", "").replace("7", "").replace("8", "")
                .replace("<", "«").replace(">", "»").replace("-", "━");
    }

    private static List<String> render(ToolTabBarElement bar, int width) {
        Rect area = new Rect(0, 0, width, ToolTabBarElement.ROWS);
        Buffer buffer = Buffer.empty(area);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null).engine());
        bar.render(Frame.forTesting(buffer), area, context);
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < ToolTabBarElement.ROWS; y++) {
            StringBuilder text = new StringBuilder();
            for (int x = 0; x < width; x++) {
                text.append(buffer.get(x, y).symbol());
            }
            rows.add(text.toString());
        }
        return rows;
    }
}
