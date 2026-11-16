package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import org.junit.jupiter.api.Test;

class ToolStripElementTest {

    private static final Rect VIEWPORT = new Rect(0, 0, 100, 30);
    private static final Rect STRIP = new Rect(0, 0, ToolStripElement.WIDTH, 30);
    private static final List<ToolStripElement.Icon> ICONS = List.of(
            new ToolStripElement.Icon("one", "A", false, false),
            new ToolStripElement.Icon("two", "S", true, false),
            new ToolStripElement.Icon("three", "R", true, true));

    @Test
    void theLeftStripKeepsItsLastColumnBlankForTheGapAndTheSelectedIconStopsBeforeIt() {
        ShellThemes themes = themes();
        Buffer buffer = render(new ToolStripElement("strip", ToolStripElement.Side.LEFT, () -> ICONS), themes, STRIP);
        Color selected = themes.color("tab-selected-bg");

        assertEquals("S", buffer.get(1, 1).symbol());
        assertEquals(selected, bg(buffer, 0, 1));
        assertEquals(selected, bg(buffer, 1, 1));
        assertEquals(" ", buffer.get(2, 1).symbol());
        assertNotEquals(selected, bg(buffer, 2, 1), "the column next to the pane is not part of the selected icon");
        assertEquals("R", buffer.get(1, 29).symbol());
    }

    @Test
    void theRightStripKeepsItsFirstColumnBlankForTheGapAndTheSelectedIconStopsBeforeIt() {
        ShellThemes themes = themes();
        Buffer buffer = render(new ToolStripElement("strip", ToolStripElement.Side.RIGHT, () -> ICONS), themes, STRIP);
        Color selected = themes.color("tab-selected-bg");

        assertEquals("S", buffer.get(2, 1).symbol());
        assertEquals(selected, bg(buffer, 1, 1));
        assertEquals(selected, bg(buffer, 2, 1));
        assertEquals(" ", buffer.get(0, 1).symbol());
        assertNotEquals(selected, bg(buffer, 0, 1), "the column next to the pane is not part of the selected icon");
        assertEquals("R", buffer.get(2, 29).symbol());
    }

    @Test
    void aGapColumnSeparatesTheLeftStripLettersFromThePaneOnEveryRowOfTheStrip() {
        Buffer buffer = shellFrame();
        for (String letter : List.of("P", "R", "T")) {
            int row = findRow(buffer, 1, letter);
            assertEquals(letter, buffer.get(1, row).symbol());
            assertEquals("▐", buffer.get(2, row).symbol(), "the gap cell follows the letter " + letter);
        }
        int footerRow = buffer.height() - 2;
        assertEquals("P", buffer.get(1, footerRow).symbol(), "the bottom group ends on the footer row");
        assertEquals("▐", buffer.get(2, footerRow).symbol());
        assertEquals("▖", buffer.get(3, footerRow).symbol(),
                "the pane corner is drawn in the pane, one cell after the gap, not against the letter");
        for (int row = 1; row < buffer.height() - 1; row++) {
            assertEquals("▐", buffer.get(2, row).symbol(), "row " + row + " of the left gap");
        }
    }

    @Test
    void aGapColumnSeparatesTheRightStripLettersFromTheShellOnEveryRowOfTheStrip() {
        Buffer buffer = shellFrame();
        int width = buffer.width();
        for (String letter : List.of("A", "S")) {
            int row = findRow(buffer, width - 1, letter);
            assertEquals(letter, buffer.get(width - 1, row).symbol());
            assertEquals(" ", buffer.get(width - 2, row).symbol());
            assertEquals("▌", buffer.get(width - 3, row).symbol(), "the gap cell precedes the letter " + letter);
        }
        for (int row = 1; row < buffer.height() - 1; row++) {
            assertEquals("▌", buffer.get(width - 3, row).symbol(), "row " + row + " of the right gap");
        }
    }

    private static Buffer shellFrame() {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            ShellThemes themes = themes();
            List<ToolWindow> windows = new ArrayList<>(new ToolWindowRegistry(List.of(
                    new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                    new ProblemsToolWindow(() -> themes), new AcpAgentsToolWindow(() -> themes),
                    new StructureToolWindow(() -> themes))).toolWindows());
            VintelliShell shell = new VintelliShell(ShellConfig.of(windows, new ShellState(), themes));
            return render(shell.root(), themes, VIEWPORT);
        } finally {
            if (previous == null) {
                System.clearProperty(IconMode.CONFIG_PROPERTY);
            } else {
                System.setProperty(IconMode.CONFIG_PROPERTY, previous);
            }
        }
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private static Buffer render(Element root, ShellThemes themes, Rect area) {
        Buffer buffer = Buffer.empty(area);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        root.render(Frame.forTesting(buffer), area, context);
        context.registerElement(root, area);
        return buffer;
    }

    private static Color bg(Buffer buffer, int x, int row) {
        Optional<Color> color = buffer.get(x, row).style().bg();
        return color.orElse(null);
    }

    private static int findRow(Buffer buffer, int x, String symbol) {
        for (int row = 0; row < buffer.height(); row++) {
            if (symbol.equals(buffer.get(x, row).symbol())) {
                return row;
            }
        }
        return -1;
    }
}
