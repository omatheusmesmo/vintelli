package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPaletteControllerTest {

    private static final List<KeyAction> ACTIONS = List.of(
            new KeyAction("Alpha", "Alpha action"),
            new KeyAction("Beta", "Beta action"),
            new KeyAction("Gamma", "Gamma action"),
            new KeyAction("Delta", "Delta action"));

    private static final OverlayRegion PANEL = new OverlayRegion(0, 0, 20, 6);
    private static final OverlayRegion INNER = PANEL.inset(1);

    private final CommandPaletteController palette = new CommandPaletteController(ACTIONS);

    @Test
    void openShowsTheWholeCatalogAndEnterConfirmsTheTopRow() {
        palette.openWith("");

        assertTrue(palette.isOpen());
        assertEquals(ACTIONS.size(), palette.matchCount());
        assertEquals(Optional.of(ACTIONS.get(0)), palette.press(OverlayKey.ENTER, 0));
    }

    @Test
    void filterMatchesLabelAndIdCaseInsensitivelyAndBackspaceRestoresIt() {
        palette.openWith("");

        type("GAM");
        assertEquals(1, palette.matchCount());
        assertEquals(Optional.of(ACTIONS.get(2)), palette.press(OverlayKey.ENTER, 0));

        for (int index = 0; index < 3; index++) {
            palette.press(OverlayKey.BACKSPACE, 0);
        }
        assertEquals(ACTIONS.size(), palette.matchCount());
    }

    @Test
    void anEmptyResultNeverConfirmsAnActionAndKeepsThePaletteOpen() {
        palette.openWith("");
        type("zzz");

        assertEquals(0, palette.matchCount());
        assertEquals(Optional.empty(), palette.press(OverlayKey.ENTER, 0));
        assertTrue(palette.isOpen());
    }

    @Test
    void arrowKeysClampAtBothEndsOfTheResults() {
        palette.openWith("");

        for (int index = 0; index < ACTIONS.size() + 2; index++) {
            palette.press(OverlayKey.DOWN, 0);
        }
        assertEquals(Optional.of(ACTIONS.get(ACTIONS.size() - 1)), palette.press(OverlayKey.ENTER, 0));

        for (int index = 0; index < ACTIONS.size() + 2; index++) {
            palette.press(OverlayKey.UP, 0);
        }
        assertEquals(Optional.of(ACTIONS.get(0)), palette.press(OverlayKey.ENTER, 0));
    }

    @Test
    void narrowingTheFilterClampsTheSelectionIntoTheShorterList() {
        palette.openWith("");
        for (int index = 0; index < 3; index++) {
            palette.press(OverlayKey.DOWN, 0);
        }

        type("ta");

        assertEquals(2, palette.matchCount());
        assertEquals(Optional.of(ACTIONS.get(3)), palette.press(OverlayKey.ENTER, 0));
    }

    @Test
    void reopeningResetsTheFilterAndTheSelection() {
        palette.openWith("");
        type("run");
        palette.press(OverlayKey.ESCAPE, 0);

        palette.openWith("");

        assertEquals(ACTIONS.size(), palette.matchCount());
        assertEquals(0, palette.selectedIndex());
    }

    @Test
    void escapeClosesAndFurtherKeysAreIgnored() {
        palette.openWith("");

        palette.press(OverlayKey.ESCAPE, 0);

        assertFalse(palette.isOpen());
        assertEquals(Optional.empty(), palette.press(OverlayKey.ENTER, 0));
    }

    @Test
    void theSelectionStaysInsideTheVisibleWindowWhileMovingDown() {
        CommandPaletteController long10 = new CommandPaletteController(tenActions());
        long10.openWith("");
        long10.layout(PANEL, INNER);

        for (int index = 0; index < 5; index++) {
            long10.press(OverlayKey.DOWN, 0);
        }
        long10.layout(PANEL, INNER);

        assertEquals(3, long10.firstVisible());
        assertEquals(5, long10.selectedIndex());
        assertEquals(tenActions().get(5), long10.visibleActions().get(2));
    }

    @Test
    void theWheelScrollsTheWindowByThreeRowsOverTheRows() {
        CommandPaletteController long10 = new CommandPaletteController(tenActions());
        long10.openWith("");
        long10.layout(PANEL, INNER);

        long10.pointer(INNER.x() + 1, INNER.y() + 1, false, 1, 0L);
        long10.layout(PANEL, INNER);

        assertEquals(3, long10.firstVisible());
        assertEquals(3, long10.selectedIndex());
    }

    @Test
    void aSecondClickOnTheSameRowWithinTheWindowConfirmsIt() {
        palette.openWith("");
        palette.layout(PANEL, INNER);
        int x = INNER.x() + 1;
        int row = INNER.y() + 2;

        assertEquals(Optional.empty(), palette.pointer(x, row, true, 0, 1_000L));
        assertEquals(Optional.of(ACTIONS.get(1)), palette.pointer(x, row, true, 0, 1_000L + 100_000_000L));
    }

    @Test
    void aClickOutsideThePanelClosesThePalette() {
        palette.openWith("");
        palette.layout(PANEL, INNER);

        palette.pointer(PANEL.x() + PANEL.width() + 5, PANEL.y(), true, 0, 0L);

        assertFalse(palette.isOpen());
    }

    @Test
    void aPointerMoveWithoutAClickNeverConfirmsOrCloses() {
        palette.openWith("");
        palette.layout(PANEL, INNER);

        assertEquals(Optional.empty(), palette.pointer(INNER.x() + 1, INNER.y() + 2, false, 0, 0L));
        assertTrue(palette.isOpen());
    }

    private void type(String text) {
        for (int index = 0; index < text.length(); index++) {
            palette.press(OverlayKey.TEXT, text.charAt(index));
        }
    }

    private static List<KeyAction> tenActions() {
        List<KeyAction> actions = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            actions.add(new KeyAction("Action" + index, "Action " + index));
        }
        return actions;
    }
}
