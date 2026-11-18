package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusEditorKeymapTest {

    @Test
    void ctrlF11IsTheFocusEditorChordInEveryProfileAndModeAndNoOtherActionAnswersIt() {
        KeyEvent chord = KeyEvent.ofKey(KeyCode.F11, KeyModifiers.CTRL);
        for (KeymapProfile profile : KeymapProfile.values()) {
            for (KeyboardMode mode : KeyboardMode.values()) {
                Keymap keymap = Keymap.ofProfile(profile, mode, List.of());
                Set<String> answering = new LinkedHashSet<>();
                for (KeyAction action : keymap.actions()) {
                    if (keymap.matches(chord, action.id())) {
                        answering.add(action.id());
                    }
                }
                assertEquals(Set.of(KeyActions.FOCUS_EDITOR), answering,
                        profile.displayName() + " in " + mode + " mode: Ctrl+F11 must focus the editor only");
            }
        }
    }

    @Test
    void thePaletteCatalogListsFocusEditorUnderItsLabel() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, List.of());

        assertTrue(keymap.actions().contains(new KeyAction(KeyActions.FOCUS_EDITOR, "Focus Editor")),
                "the command palette lists the action with its label");
    }
}
