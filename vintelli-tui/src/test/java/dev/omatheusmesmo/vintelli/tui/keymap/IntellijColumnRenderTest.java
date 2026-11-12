package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.AcpAgentsToolWindow;
import dev.omatheusmesmo.vintelli.tui.ProblemsToolWindow;
import dev.omatheusmesmo.vintelli.tui.RunToolWindow;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.TerminalToolWindow;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntellijColumnRenderTest {

    private static final List<ToolWindow> WINDOWS = windowList();
    private static final Rect PALETTE_AREA = new Rect(0, 0, 80, 30);

    private static List<ToolWindow> windowList() {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        return List.of(new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                new ProblemsToolWindow(() -> themes), new AcpAgentsToolWindow(() -> themes));
    }

    @Test
    void paletteRowOfRunShowsNoIntellijChordWhileTheActiveChordMatchesIt() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        String row = paletteRow(keymap, "activate run", "Activate Run Tool Window");

        assertTrue(row.contains("Alt+4"), row);
        assertFalse(row.contains("IntelliJ"), "Run already answers to Alt+4, nothing extra to show: " + row);
    }

    @Test
    void paletteRowOfRunShowsTheIntellijChordWhileTheActiveChordDiffers(@TempDir Path directory) throws IOException {
        Path userFile = Files.writeString(directory.resolve("keymap.properties"), "ActivateRunToolWindow = F6\n");
        Keymap keymap = Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile);

        String row = paletteRow(keymap, "activate run", "Activate Run Tool Window");

        assertTrue(row.contains("F6"), row);
        assertTrue(row.contains("(IntelliJ: Alt+4)"), "the palette shows the IntelliJ chord next to the active one: " + row);
    }

    @Test
    void paletteRowOfSearchEverywhereHidesTheDoubleShiftChordTerminalsCannotSend() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        String row = paletteRow(keymap, "search ev", "Search Everywhere");

        assertTrue(row.contains("Ctrl+p"), row);
        assertFalse(row.contains("Double Shift"), "a chord a terminal cannot send is not shown in the palette: " + row);
    }

    @Test
    void paletteRowOfAnUnboundActionShowsItsIntellijChord() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        String row = paletteRow(keymap, "open settings", "Open Settings");

        assertTrue(row.contains("(IntelliJ: Ctrl+Alt+S)"), row);
    }

    @Test
    void helpRowCutsTheIntellijColumnFirstAndKeepsTheNameAndTheActiveChord() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);
        String terminal = KeyActions.activateToolWindow("terminal");
        String row = helpRow(keymap, new Rect(0, 0, 52, 130), "Activate Terminal Tool Window");

        assertTrue(row.contains("Activate Terminal Tool Window"), "the action name is never cut: " + row);
        assertTrue(row.contains(keymap.describeBindings(terminal)), "the active chord is never cut: " + row);
        assertFalse(row.contains("IntelliJ"), "the IntelliJ column is the part that gives way: " + row);
    }

    @Test
    void helpRowAt80ColumnsShowsTheIntellijChordAndWhyATerminalCannotSendIt() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);
        String row = helpRow(keymap, new Rect(0, 0, 80, 130), "Search Everywhere");

        assertTrue(row.contains("Double Shift"), row);
        assertTrue(row.contains("not sent by terminals"), row);
    }

    @Test
    void helpRowAt80ColumnsKeepsTheNameAndTheActiveChordOfEveryAction() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);
        String rendered = render(new Rect(0, 0, 80, 130), (frame, area) -> {
            KeymapHelpOverlay overlay = new KeymapHelpOverlay(keymap);
            overlay.open();
            overlay.render(frame, area);
        });

        for (KeyAction action : keymap.actions()) {
            String row = rendered.lines().filter(line -> line.contains(action.label())).findFirst()
                    .orElseThrow(() -> new AssertionError("no help row for " + action.label()));
            assertTrue(row.contains(keymap.describeBindings(action.id())), action.id() + " lost its chord: " + row);
        }
    }

    private static String paletteRow(Keymap keymap, String filter, String label) {
        CommandPalette palette = new CommandPalette(keymap.actions(), keymap);
        palette.open();
        for (char character : filter.toCharArray()) {
            palette.handleKey(KeyEvent.ofChar(character));
        }
        String rendered = render(PALETTE_AREA, (frame, area) -> palette.render(frame, area));
        return rendered.lines().filter(line -> line.contains(label) && !line.contains("╭")).findFirst()
                .orElseThrow(() -> new AssertionError("no palette row for " + label + " in:\n" + rendered));
    }

    private static String helpRow(Keymap keymap, Rect area, String label) {
        KeymapHelpOverlay overlay = new KeymapHelpOverlay(keymap);
        overlay.open();
        String rendered = render(area, (frame, rect) -> overlay.render(frame, rect));
        return rendered.lines().filter(line -> line.contains(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no help row for " + label + " in:\n" + rendered));
    }

    private static String render(Rect area, BiConsumer<Frame, Rect> draw) {
        Buffer buffer = Buffer.empty(area);
        draw.accept(Frame.forTesting(buffer), area);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < area.height(); y++) {
            for (int x = 0; x < area.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
