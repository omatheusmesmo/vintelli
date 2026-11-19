package dev.omatheusmesmo.vintelli.tui.keymap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

/**
 * Quick Definition is Ctrl+Shift+I in the intellij profile, where the Kitty keyboard protocol delivers it, and
 * Alt+Space in every profile for the terminals that send Ctrl+Shift+I as the Tab byte. Each chord answers exactly
 * one action, and the plain Ctrl+I and a bare space stay what they were.
 */
class QuickDefinitionKeymapTest {

    private static final Keymap INTELLIJ = Keymap.ofProfile(KeymapProfile.INTELLIJ, KeyboardMode.AUTO, List.of());
    private static final Keymap VIM = Keymap.ofProfile(KeymapProfile.VIM, KeyboardMode.AUTO, List.of());
    private static final Keymap LEGACY = Keymap.ofProfile(KeymapProfile.INTELLIJ, KeyboardMode.LEGACY, List.of());
    private static final KeyEvent CTRL_SHIFT_I = KeyEvent.builder().code(KeyCode.CHAR)
            .modifiers(KeyModifiers.of(true, false, true)).character('I').keyCodePoint('i')
            .shiftedKeyCodePoint('I').baseLayoutKeyCodePoint('i').build();
    private static final KeyEvent ALT_SPACE = KeyEvent.ofChar(' ', KeyModifiers.ALT);

    @Test
    void ctrlShiftIPeeksAtTheDefinitionOnlyWhereTheKittyModeSendsIt() {
        assertEquals(Set.of(KeyActions.QUICK_DEFINITION), answering(INTELLIJ, CTRL_SHIFT_I));
        assertEquals(Set.of(), answering(LEGACY, CTRL_SHIFT_I), "legacy terminals send the Tab byte instead");
        assertEquals(Set.of(), answering(VIM, CTRL_SHIFT_I), "the vim profile binds only the fallback");
    }

    @Test
    void altSpaceIsTheFallbackInEveryProfileAndMode() {
        assertEquals(Set.of(KeyActions.QUICK_DEFINITION), answering(INTELLIJ, ALT_SPACE));
        assertEquals(Set.of(KeyActions.QUICK_DEFINITION), answering(VIM, ALT_SPACE));
        assertEquals(Set.of(KeyActions.QUICK_DEFINITION), answering(LEGACY, ALT_SPACE));
    }

    @Test
    void aPlainCtrlIAndABareSpaceAreNotQuickDefinition() {
        assertEquals(Set.of(), answering(INTELLIJ, KeyEvent.ofChar('i', KeyModifiers.CTRL)));
        assertEquals(Set.of(), answering(INTELLIJ, KeyEvent.ofChar(' ')));
    }

    @Test
    void theActionIsInTheCatalogSoThePaletteListsIt() {
        assertTrue(INTELLIJ.actions().stream().anyMatch(action -> action.id().equals(KeyActions.QUICK_DEFINITION)));
    }

    private static Set<String> answering(Keymap keymap, KeyEvent key) {
        return keymap.actions().stream().map(KeyAction::id).filter(id -> keymap.matches(key, id))
                .collect(Collectors.toSet());
    }
}
