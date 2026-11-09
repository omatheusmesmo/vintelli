package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorStartScreenTest {

    private static final Path PROJECT = Path.of("/work/vintelli-sample");
    private static final Rect SMALL = new Rect(0, 0, 80, 24);
    private static final Rect LARGE = new Rect(0, 0, 150, 42);
    private static final Pattern ISSUE_NUMBER = Pattern.compile("#\\d+");

    @Test
    void shortcutsAreReadFromTheActiveKeymapAndUnboundActionsHaveNoRow() {
        List<EditorStartScreen.Shortcut> shortcuts = EditorStartScreen.shortcuts(
                Keymap.ofProfile(KeymapProfile.DEFAULT, List.of()));

        assertEquals(List.of(
                new EditorStartScreen.Shortcut("Go to File", "Ctrl+o"),
                new EditorStartScreen.Shortcut("Find in Files", "Alt+f"),
                new EditorStartScreen.Shortcut("Search Everywhere", "Ctrl+p"),
                new EditorStartScreen.Shortcut("Open Menu", "F10"),
                new EditorStartScreen.Shortcut("Keymap Help", "F1")), shortcuts);
    }

    @Test
    void aChangedBindingShowsTheUserChordInsteadOfTheProfileChord(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "navigation_goToFile = Ctrl+g\n");
        Keymap keymap = Keymap.load(KeymapProfile.DEFAULT, List.of(), userFile);

        List<EditorStartScreen.Shortcut> shortcuts = EditorStartScreen.shortcuts(keymap);

        assertTrue(shortcuts.contains(new EditorStartScreen.Shortcut("Go to File", "Ctrl+g")),
                shortcuts.toString());
        assertFalse(shortcuts.contains(new EditorStartScreen.Shortcut("Go to File", "Ctrl+o")),
                "the profile chord must not survive a user binding");
    }

    @Test
    void theScreenNamesTheProjectAndShowsNoIssueNumbers() {
        String screen = text(render(keymap(), SMALL, themes()));

        assertTrue(screen.contains("vintelli-sample"), screen);
        assertFalse(ISSUE_NUMBER.matcher(screen).find(), screen);
        assertFalse(screen.contains("lands with"), screen);
        assertFalse(screen.contains("external editor"), screen);
    }

    @Test
    void theBlockFitsAt80x24AndIsCentred() {
        assertFitsAndCentred(SMALL);
    }

    @Test
    void theBlockStaysCentredOnALargerScreen() {
        assertFitsAndCentred(LARGE);
    }

    @Test
    void theKeysUseTheHintRoleAndTheTextUsesTheTextRole() {
        ShellThemes themes = themes();
        Buffer buffer = render(keymap(), SMALL, themes);

        int keyRow = rowContaining(buffer, "Ctrl+o");
        int keyColumn = rowText(buffer, keyRow).indexOf("Ctrl+o");
        int titleRow = rowContaining(buffer, "vintelli-sample");
        int titleColumn = rowText(buffer, titleRow).indexOf("vintelli-sample");

        assertEquals(themes.resolver("editor-start-hint").toStyle().fg().orElse(null),
                buffer.get(keyColumn, keyRow).style().fg().orElse(null));
        assertEquals(themes.resolver("editor-start-text").toStyle().fg().orElse(null),
                buffer.get(titleColumn, titleRow).style().fg().orElse(null));
    }

    @Test
    void theShellShowsTheStartScreenWhileNoFileIsOpen(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "navigation_goToFile = Ctrl+g\n");
        Keymap keymap = Keymap.load(KeymapProfile.DEFAULT, List.of(), userFile);
        VintelliShell shell = new VintelliShell(List.of(), new ShellState(), () -> keymap, themes());

        Buffer buffer = renderShell(shell, SMALL);
        String frame = text(buffer);

        assertTrue(frame.contains("Go to File"), frame);
        assertTrue(frame.contains("Ctrl+g"), frame);
        assertFalse(frame.contains("Ctrl+o"), frame);
        assertFalse(ISSUE_NUMBER.matcher(frame).find(), frame);
        assertFalse(frame.contains("lands with"), frame);
    }

    private static void assertFitsAndCentred(Rect area) {
        Buffer buffer = render(keymap(), area, themes());
        int top = -1;
        int bottom = -1;
        int left = Integer.MAX_VALUE;
        int right = -1;
        for (int row = 0; row < area.height(); row++) {
            String line = rowText(buffer, row);
            if (line.isBlank()) {
                continue;
            }
            top = top < 0 ? row : top;
            bottom = row;
            left = Math.min(left, indexOfFirstNonBlank(line));
            right = Math.max(right, line.stripTrailing().length() - 1);
        }
        assertTrue(top >= 0, "the start screen draws something");
        assertTrue(right < area.width() && bottom < area.height(), "the block fits in " + area);
        int leftMargin = left;
        int rightMargin = area.width() - 1 - right;
        int topMargin = top;
        int bottomMargin = area.height() - 1 - bottom;
        assertTrue(Math.abs(leftMargin - rightMargin) <= 1,
                "horizontal centring: left " + leftMargin + " right " + rightMargin);
        assertTrue(Math.abs(topMargin - bottomMargin) <= 1,
                "vertical centring: top " + topMargin + " bottom " + bottomMargin);
        String screen = text(buffer);
        assertTrue(screen.contains("Keymap Help"), screen);
    }

    private static Keymap keymap() {
        return Keymap.ofProfile(KeymapProfile.DEFAULT, List.of());
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private static Buffer render(Keymap keymap, Rect area, ShellThemes themes) {
        Buffer buffer = Buffer.empty(area);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        Element element = new EditorStartScreen(PROJECT, () -> keymap, themes).element();
        element.render(Frame.forTesting(buffer), area, context);
        return buffer;
    }

    private static Buffer renderShell(VintelliShell shell, Rect area) {
        Buffer buffer = Buffer.empty(area);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(shell.themes().engine());
        Element root = shell.root();
        root.render(Frame.forTesting(buffer), area, context);
        return buffer;
    }

    private static int rowContaining(Buffer buffer, String text) {
        for (int row = 0; row < buffer.height(); row++) {
            if (rowText(buffer, row).contains(text)) {
                return row;
            }
        }
        throw new AssertionError("no row shows " + text + " in " + text(buffer));
    }

    private static int indexOfFirstNonBlank(String line) {
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) != ' ') {
                return index;
            }
        }
        return line.length();
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString();
    }

    private static String text(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < buffer.height(); row++) {
            text.append(rowText(buffer, row)).append('\n');
        }
        return text.toString();
    }
}
