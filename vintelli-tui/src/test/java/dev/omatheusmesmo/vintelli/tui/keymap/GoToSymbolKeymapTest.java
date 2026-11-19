package dev.omatheusmesmo.vintelli.tui.keymap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Go to Class is Ctrl+N and Go to Symbol is Ctrl+Alt+Shift+N in the Kitty mode, while Ctrl+Shift+N stays Go to File.
 * Each chord answers exactly one action, so the three never start each other.
 */
class GoToSymbolKeymapTest {

    private static final Keymap INTELLIJ = Keymap.ofProfile(KeymapProfile.INTELLIJ, KeyboardMode.AUTO, List.of());
    private static final Keymap VIM = Keymap.ofProfile(KeymapProfile.VIM, KeyboardMode.AUTO, List.of());
    private static final Keymap LEGACY = Keymap.ofProfile(KeymapProfile.INTELLIJ, KeyboardMode.LEGACY, List.of());
    private static final KeyModifiers CTRL_ALT_SHIFT = KeyModifiers.of(true, true, true);

    @Test
    void ctrlNOpensGoToClassInBothProfiles() {
        KeyEvent ctrlN = KeyEvent.ofChar('n', KeyModifiers.CTRL);

        assertEquals(Set.of(KeyActions.GO_TO_CLASS), answering(INTELLIJ, ctrlN), "intellij");
        assertEquals(Set.of(KeyActions.GO_TO_CLASS), answering(VIM, ctrlN), "vim");
        assertEquals(Set.of(KeyActions.GO_TO_CLASS), answering(LEGACY, ctrlN), "legacy");
    }

    @Test
    void ctrlShiftNStaysGoToFileAndDoesNotOpenGoToClass() {
        KeyEvent ctrlShiftN = KeyEvent.builder().code(KeyCode.CHAR).modifiers(KeyModifiers.of(true, false, true))
                .character('N').keyCodePoint('n').shiftedKeyCodePoint('N').baseLayoutKeyCodePoint('n').build();

        assertEquals(Set.of(KeyActions.GO_TO_FILE), answering(INTELLIJ, ctrlShiftN));
    }

    @Test
    void ctrlAltShiftNOpensGoToSymbolOnlyWhereTheKittyModeSendsIt() {
        KeyEvent ctrlAltShiftN = KeyEvent.builder().code(KeyCode.CHAR).modifiers(CTRL_ALT_SHIFT)
                .character('N').keyCodePoint('n').shiftedKeyCodePoint('N').baseLayoutKeyCodePoint('n').build();
        KeyEvent ctrlAltN = KeyEvent.ofChar('n', KeyModifiers.of(true, true, false));

        assertEquals(Set.of(KeyActions.GO_TO_SYMBOL), answering(INTELLIJ, ctrlAltShiftN));
        assertEquals(Set.of(), answering(LEGACY, ctrlAltShiftN), "legacy terminals do not send the chord");
        assertEquals(Set.of(), answering(INTELLIJ, ctrlAltN), "Ctrl+Alt+N without Shift is no chord of ours");
    }

    private static Set<String> answering(Keymap keymap, KeyEvent key) {
        return keymap.actions().stream().map(KeyAction::id).filter(id -> keymap.matches(key, id))
                .collect(Collectors.toSet());
    }
}
