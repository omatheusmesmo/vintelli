package dev.omatheusmesmo.vintelli.tui.keymap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Alt+Insert generates code in the intellij profile only, the way IntelliJ binds Generate. The vim profile binds
 * no chord, and the command palette is the way to reach the action where the terminal does not send the chord.
 */
class GenerateKeymapTest {

    private static final Keymap INTELLIJ = Keymap.ofProfile(KeymapProfile.INTELLIJ, List.of());
    private static final Keymap VIM = Keymap.ofProfile(KeymapProfile.VIM, List.of());

    @Test
    void altInsertGeneratesInTheIntellijProfile() {
        assertTrue(INTELLIJ.matches(KeyEvent.ofKey(KeyCode.INSERT, KeyModifiers.ALT), KeyActions.GENERATE));
    }

    @Test
    void theVimProfileBindsNoGenerateChord() {
        assertFalse(VIM.matches(KeyEvent.ofKey(KeyCode.INSERT, KeyModifiers.ALT), KeyActions.GENERATE));
    }

    @Test
    void aBareInsertAndAltEnterAreNotGenerate() {
        assertFalse(INTELLIJ.matches(KeyEvent.ofKey(KeyCode.INSERT, KeyModifiers.NONE), KeyActions.GENERATE));
        assertFalse(INTELLIJ.matches(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.ALT), KeyActions.GENERATE));
        assertFalse(INTELLIJ.matches(KeyEvent.ofKey(KeyCode.INSERT, KeyModifiers.ALT), KeyActions.QUICK_FIX));
    }

    @Test
    void theActionIsInTheCatalogSoThePaletteListsIt() {
        assertTrue(INTELLIJ.actions().stream().anyMatch(action -> action.id().equals(KeyActions.GENERATE)
                && action.label().equals("Generate")));
    }
}
