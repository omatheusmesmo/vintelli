package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.AcpAgentsToolWindow;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import java.io.IOException;
import java.nio.file.Path;
import dev.omatheusmesmo.vintelli.tui.ProblemsToolWindow;
import dev.omatheusmesmo.vintelli.tui.RunToolWindow;
import dev.omatheusmesmo.vintelli.tui.TerminalToolWindow;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPaletteTest {

    private static final List<ToolWindow> WINDOWS = windowList();

    private static List<ToolWindow> windowList() {
        ShellThemes themes = themes();
        return List.of(new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes), new ProblemsToolWindow(() -> themes),
                new AcpAgentsToolWindow(() -> themes));
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private final Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);
    private final CommandPalette palette = new CommandPalette(keymap.actions(), keymap);

    @Test
    void confirmsTheFilteredActionOnEnterWithCaseInsensitiveLabelMatching() {
        palette.open();
        assertTrue(palette.isOpen(), "the palette must open");

        assertEquals(Optional.of(action("ActivateRunToolWindow")), pick("activate run"));
        assertEquals(Optional.of(action("ActivateProblemsToolWindow")), pick("PROBLEM"));
        assertEquals(Optional.of(action("ActivateAcpAgentsToolWindow")), pick("acp agents"));
        assertEquals(Optional.of(action("quit")), pick("quit"));
    }

    @Test
    void typingTheWholeActionIdLowerCasedReachesEveryAction() {
        for (KeyAction expected : keymap.actions()) {
            CommandPalette fresh = new CommandPalette(keymap.actions(), keymap);
            fresh.open();
            for (int index = 0; index < expected.id().length(); index++) {
                fresh.handleKey(KeyEvent.ofChar(Character.toLowerCase(expected.id().charAt(index))));
            }
            assertEquals(Optional.of(expected), fresh.handleKey(KeyEvent.ofKey(KeyCode.ENTER)),
                    "every action must be reachable from the palette by its id");
        }
    }

    @Test
    void arrowKeysMoveTheSelectionWithinTheFilteredResultBounds() {
        palette.open();
        palette.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        assertEquals(Optional.of(action("ActivateProblemsToolWindow")),
                palette.handleKey(KeyEvent.ofKey(KeyCode.ENTER)),
                "down moves to the second result of the alphabetical catalog");

        int beyondCatalog = keymap.actions().size() + 1;
        for (int index = 0; index < beyondCatalog; index++) {
            palette.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        }
        // The keymap catalog is sorted by label, with Widen Project Panel last.
        assertEquals(Optional.of(action("layout_project_wider")), palette.handleKey(KeyEvent.ofKey(KeyCode.ENTER)),
                "down clamps at the last result");

        for (int index = 0; index < beyondCatalog; index++) {
            palette.handleKey(KeyEvent.ofKey(KeyCode.UP));
        }
        assertEquals(Optional.of(action("ActivateAcpAgentsToolWindow")), palette.handleKey(KeyEvent.ofKey(KeyCode.ENTER)),
                "up clamps at the first result");
    }

    @Test
    void narrowingTheFilterClampsTheSelectionIntoTheShorterList() {
        palette.open();
        for (int index = 0; index < 3; index++) {
            palette.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        }
        type("activate");
        assertEquals(Optional.of(action("ActivateTerminalToolWindow")),
                palette.handleKey(KeyEvent.ofKey(KeyCode.ENTER)),
                "the selection must clamp when the filter shortens the result list");
    }

    @Test
    void backspaceEditsTheFilterAndAnEmptyResultConfirmsNothing() {
        palette.open();
        type("runxyz");
        assertEquals(Optional.empty(), palette.handleKey(KeyEvent.ofKey(KeyCode.ENTER)),
                "an empty result list never confirms an action");
        assertTrue(palette.isOpen(), "confirming with no result keeps the palette open");

        for (int index = 0; index < 3; index++) {
            palette.handleKey(KeyEvent.ofKey(KeyCode.BACKSPACE));
        }
        assertEquals(Optional.of(action("ActivateRunToolWindow")), palette.handleKey(KeyEvent.ofKey(KeyCode.ENTER)),
                "backspace must remove filter characters and restore the matches");

        palette.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(palette.isOpen(), "escape closes the palette");
    }

    @Test
    void reopeningResetsFilterAndSelection() {
        palette.open();
        type("run");
        palette.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));

        palette.open();
        assertEquals(Optional.of(action("ActivateAcpAgentsToolWindow")), palette.handleKey(KeyEvent.ofKey(KeyCode.ENTER)),
                "a fresh open shows the whole catalog with the selection at the top");
    }

    private Optional<KeyAction> pick(String filter) {
        palette.open();
        type(filter);
        Optional<KeyAction> picked = palette.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
        palette.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));
        return picked;
    }

    private KeyAction action(String id) {
        return keymap.actions().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void type(String filter) {
        for (int index = 0; index < filter.length(); index++) {
            palette.handleKey(KeyEvent.ofChar(filter.charAt(index)));
        }
    }
}
