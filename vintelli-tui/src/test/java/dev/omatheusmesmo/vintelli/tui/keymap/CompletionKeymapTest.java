package dev.omatheusmesmo.vintelli.tui.keymap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Ctrl+Space starts code completion in both profiles. Some terminals send Ctrl+Space as the NUL byte, which the
 * parser reads as Ctrl+Space, and others send Ctrl+@; both reach the same action.
 */
class CompletionKeymapTest {

    private static final Keymap INTELLIJ = Keymap.ofProfile(KeymapProfile.INTELLIJ, List.of());
    private static final Keymap VIM = Keymap.ofProfile(KeymapProfile.VIM, List.of());

    @Test
    void ctrlSpaceAndTheNulByteStartCompletionInBothProfiles() {
        KeyEvent ctrlSpace = KeyEvent.ofChar(' ', KeyModifiers.CTRL);

        assertTrue(INTELLIJ.matches(ctrlSpace, KeyActions.COMPLETION), "Ctrl+Space in the intellij profile");
        assertTrue(VIM.matches(ctrlSpace, KeyActions.COMPLETION), "Ctrl+Space in the vim profile");
    }

    @Test
    void ctrlAtStartsCompletionForTerminalsThatSendItAsAt() {
        KeyEvent ctrlAt = KeyEvent.ofChar('@', KeyModifiers.CTRL);

        assertTrue(INTELLIJ.matches(ctrlAt, KeyActions.COMPLETION));
        assertTrue(VIM.matches(ctrlAt, KeyActions.COMPLETION));
    }

    @Test
    void theDefaultKeymapOfTheShellStartsCompletionWithCtrlAt() {
        Keymap shell = Keymap.defaultKeymap(List.of());

        System.out.println("CTRLAT-MATCHES " + shell.actions().stream().map(KeyAction::id)
                .filter(id -> shell.matches(KeyEvent.ofChar('@', KeyModifiers.CTRL), id)).toList());
        System.out.println("CTRLAT-SPACE " + shell.actions().stream().map(KeyAction::id)
                .filter(id -> shell.matches(KeyEvent.ofChar(' ', KeyModifiers.CTRL), id)).toList());
        assertTrue(shell.matches(KeyEvent.ofChar('@', KeyModifiers.CTRL), KeyActions.COMPLETION));
        assertTrue(shell.matches(KeyEvent.ofChar(' ', KeyModifiers.CTRL), KeyActions.COMPLETION));
    }

    @Test
    void aPlainSpaceNeverStartsCompletion() {
        assertFalse(INTELLIJ.matches(KeyEvent.ofChar(' '), KeyActions.COMPLETION));
        assertFalse(INTELLIJ.matches(KeyEvent.ofChar(' ', KeyModifiers.ALT), KeyActions.COMPLETION));
    }
}
