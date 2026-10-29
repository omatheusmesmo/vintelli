package dev.omatheusmesmo.vintelli.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolWindowRegistryTest {

    @Test
    void sortsRegisteredToolWindowsByOrdinalThenId() {
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new FakeToolWindow("zeta", "Zeta", '5', 10, "zeta content"),
                new FakeToolWindow("alpha", "Alpha", '9', 10, "alpha content"),
                new FakeToolWindow("early", "Early", '1', 0, "early content")));

        assertEquals(List.of("early", "alpha", "zeta"), ids(registry));
    }

    @Test
    void keepsAnEmptyRegistryUsable() {
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of());

        assertEquals(List.of(), ids(registry));
    }

    @Test
    void duplicateIdsFailFastNamingBothImplementors() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ToolWindowRegistry(List.of(
                new FakeToolWindow("notes", "Notes", '5', 0, "a"),
                new FakeToolWindow("notes", "Notes", '6', 1, "b"))));

        assertTrue(failure.getMessage().contains("duplicate tool window id 'notes'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("FakeToolWindow"), failure.getMessage());
    }

    @Test
    void duplicateShortcutsFailFast() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ToolWindowRegistry(List.of(
                new FakeToolWindow("notes", "Notes", '5', 0, "a"),
                new FakeToolWindow("diary", "Diary", '5', 1, "b"))));

        assertTrue(failure.getMessage().contains("already taken"), failure.getMessage());
    }

    @Test
    void shellReservedKeysFailFast() {
        for (char reserved : new char[] {'j', 'k', 'q'}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ToolWindowRegistry(
                    List.of(new FakeToolWindow("notes", "Notes", reserved, 0, "a"))),
                    "shortcut " + reserved + " must be rejected");

            assertTrue(failure.getMessage().contains("reserved for shell navigation"), failure.getMessage());
        }
    }

    @Test
    void controlCharactersFailFast() {
        for (char control : new char[] {'\r', '\n', '\t'}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new ToolWindowRegistry(
                    List.of(new FakeToolWindow("notes", "Notes", control, 0, "a"))),
                    "control character must be rejected");

            assertTrue(failure.getMessage().contains("control character"), failure.getMessage());
        }
    }

    private static List<String> ids(ToolWindowRegistry registry) {
        return registry.toolWindows().stream().map(ToolWindow::id).toList();
    }
}
