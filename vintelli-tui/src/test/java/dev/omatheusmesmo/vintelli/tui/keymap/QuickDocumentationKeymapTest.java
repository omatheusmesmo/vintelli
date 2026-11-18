package dev.omatheusmesmo.vintelli.tui.keymap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Ctrl+Q shows the quick documentation in every profile, the way IntelliJ binds it, and the chord does not start
 * anything else: a bare q is text, and Ctrl+Space keeps its completion.
 */
class QuickDocumentationKeymapTest {

    private static final Keymap INTELLIJ = Keymap.ofProfile(KeymapProfile.INTELLIJ, List.of());
    private static final Keymap VIM = Keymap.ofProfile(KeymapProfile.VIM, List.of());

    @Test
    void ctrlQShowsTheQuickDocumentationInBothProfiles() {
        KeyEvent ctrlQ = KeyEvent.ofChar('q', KeyModifiers.CTRL);

        assertTrue(INTELLIJ.matches(ctrlQ, KeyActions.QUICK_DOCUMENTATION), "Ctrl+Q in the intellij profile");
        assertTrue(VIM.matches(ctrlQ, KeyActions.QUICK_DOCUMENTATION), "Ctrl+Q in the vim profile");
    }

    @Test
    void theChordIsDistinctFromTheActionsItNeighbours() {
        KeyEvent ctrlQ = KeyEvent.ofChar('q', KeyModifiers.CTRL);

        assertFalse(INTELLIJ.matches(ctrlQ, KeyActions.COMPLETION));
        assertFalse(INTELLIJ.matches(ctrlQ, KeyActions.QUIT));
        assertFalse(INTELLIJ.matches(KeyEvent.ofChar('q'), KeyActions.QUICK_DOCUMENTATION),
                "a bare q is typed text, never the documentation");
    }

    @Test
    void theActionIsInTheCatalogSoThePaletteListsIt() {
        assertTrue(INTELLIJ.actions().stream().anyMatch(action -> action.id().equals(KeyActions.QUICK_DOCUMENTATION)));
    }
}
