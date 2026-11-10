package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.AcpAgentsToolWindow;
import dev.omatheusmesmo.vintelli.tui.ProblemsToolWindow;
import dev.omatheusmesmo.vintelli.tui.maven.MavenToolWindow;
import dev.omatheusmesmo.vintelli.tui.RunToolWindow;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.StructureToolWindow;
import dev.omatheusmesmo.vintelli.tui.TerminalToolWindow;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.tui.bindings.DefaultBindings;
import dev.tamboui.tui.bindings.InputTrigger;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntellijKeymapTableTest {

    private static final List<ToolWindow> WINDOWS = windowList();
    private static final Keymap KEYMAP = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);
    private static final KeymapTable TABLE = KeymapTable.load();
    private static final Map<String, Set<String>> BOUND_CHORDS = boundChordsByAction();
    private static final Map<String, Set<String>> ACTIONS_BY_CHORD = actionsByChord();
    private static final Map<String, Set<String>> SHARED_CHORDS_BY_DESIGN = Map.of(
            "enter", Set.of("select", "confirm", "editor_open"),
            "delete", Set.of("deleteForward", "project_delete"),
            "mouse.scroll_up", Set.of("scroll", "scrollUp"),
            "mouse.scroll_down", Set.of("scroll", "scrollDown"),
            "mouse.scroll_left", Set.of("scroll", "scrollLeft"),
            "mouse.scroll_right", Set.of("scroll", "scrollRight"));

    private static List<ToolWindow> windowList() {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        return List.of(new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                new ProblemsToolWindow(() -> themes), new AcpAgentsToolWindow(() -> themes),
                new StructureToolWindow(themes), new MavenToolWindow(() -> themes, Path.of(".")));
    }

    @Test
    void everyBoundActionHasARow() {
        for (Map.Entry<String, Set<String>> bound : BOUND_CHORDS.entrySet()) {
            assertNotNull(TABLE.rowOf(bound.getKey()), "bound action '" + bound.getKey() + "' has no row in "
                    + KeymapTable.RESOURCE + " (chords " + bound.getValue() + ")");
        }
    }

    @Test
    void everyRowNamesABoundActionWithTheChordsTheProfileBinds() {
        for (KeymapTable.Row row : TABLE.rows()) {
            Set<String> bound = BOUND_CHORDS.get(row.actionId());
            assertNotNull(bound, "row '" + row.actionId() + "' names an action the default profile does not bind");
            assertEquals(normalized(bound), normalized(row.activeChords()), "row '" + row.actionId()
                    + "' lists chords " + row.activeChords() + " but the profile binds " + bound);
        }
    }

    @Test
    void sameRowsMatchIntelliJAndDifferentRowsDoNot() {
        for (KeymapTable.Row row : TABLE.rows()) {
            if (row.status() == KeymapTable.Status.SAME) {
                assertFalse(row.intellijChords().isEmpty(), "row '" + row.actionId() + "' is same but has no IntelliJ chord");
                assertEquals(normalized(row.intellijChords()), withoutBareDigits(row.activeChords()), "row '"
                        + row.actionId() + "' says same but the active chord " + row.activeChords()
                        + " differs from IntelliJ's " + row.intellijChords());
            } else if (row.status() == KeymapTable.Status.DIFFERENT) {
                assertFalse(row.intellijChords().isEmpty(), "row '" + row.actionId() + "' is different but has no IntelliJ chord");
                assertNotEquals(normalized(row.intellijChords()), normalized(row.activeChords()),
                        "row '" + row.actionId() + "' says different but both chords are " + row.activeChords());
            }
        }
    }

    @Test
    void noChordIsBoundToTwoActionsOutsideTheSharedChordsByDesign() {
        Map<String, Set<String>> shared = new TreeMap<>();
        ACTIONS_BY_CHORD.forEach((chord, actions) -> {
            if (actions.size() > 1) {
                shared.put(chord, actions);
            }
        });
        assertEquals(SHARED_CHORDS_BY_DESIGN, shared, "chords bound to more than one action differ from the "
                + "documented shared chords (key: chord, value: actions that share it)");
    }

    @Test
    void everyDifferentOrNotDeliverableOrVintelliOnlyRowExplainsItself() {
        for (KeymapTable.Row row : TABLE.rows()) {
            if (row.status() != KeymapTable.Status.SAME) {
                assertFalse(row.reason().isBlank(), "row '" + row.actionId() + "' has status "
                        + row.status().wireName() + " but no reason");
            }
        }
    }

    @Test
    void toolWindowActivationRowsMatchTheChordsKeymapReallyBinds() {
        for (ToolWindow window : WINDOWS) {
            String actionId = KeyActions.activateToolWindow(window.id());
            KeymapTable.Row row = TABLE.rowOf(actionId);
            assertNotNull(row, "no row for " + actionId);
            Set<String> bound = boundChordsOf(actionId);
            assertFalse(bound.isEmpty(), actionId + " binds no chord");
            assertEquals(normalized(bound), normalized(row.activeChords()), "row '" + actionId + "' lists "
                    + row.activeChords() + " but Keymap binds " + bound);
            Set<String> expected = new TreeSet<>(Set.of("Alt+" + window.shortcut(), String.valueOf(window.shortcut())));
            window.extraChord().ifPresent(chord -> expected.add(chord.describe()));
            assertEquals(normalized(expected), normalized(bound),
                    actionId + " must bind Alt+<shortcut>, the bare shortcut and its extra chord");
        }
    }

    @Test
    void eachWindowOpensWithItsIntelliJChordInTheDefaultProfile() {
        assertTrue(KEYMAP.matches(KeyEvent.ofChar('1', KeyModifiers.ALT), KeyActions.ACTIVATE_PROJECT),
                "Alt+1 shows the Project pane");
        assertTrue(KEYMAP.matches(KeyEvent.ofChar('4', KeyModifiers.ALT), KeyActions.activateToolWindow("run")),
                "Alt+4 opens Run");
        assertTrue(KEYMAP.matches(KeyEvent.ofChar('6', KeyModifiers.ALT), KeyActions.activateToolWindow("problems")),
                "Alt+6 opens Problems");
        assertTrue(KEYMAP.matches(KeyEvent.ofChar('7', KeyModifiers.ALT), KeyActions.activateToolWindow("structure")),
                "Alt+7 opens Structure");
        assertTrue(KEYMAP.matches(KeyEvent.ofKey(KeyCode.F12, KeyModifiers.ALT), KeyActions.activateToolWindow("terminal")),
                "Alt+F12 opens Terminal");
        assertTrue(KEYMAP.matches(KeyEvent.ofChar('8', KeyModifiers.ALT), KeyActions.activateToolWindow("acp.agents")),
                "ACP Agents takes the number 8, a number IntelliJ does not give a window we ship");
        assertTrue(KEYMAP.matches(KeyEvent.ofChar('9', KeyModifiers.ALT), KeyActions.activateToolWindow("maven")),
                "Maven takes the number 9, a number IntelliJ does not give a window we ship");
    }

    /**
     * The bare digit of a tool window is the Vintelli shortcut for while no
     * input has focus, not an IntelliJ chord, so a same row compares the other
     * active chords with IntelliJ's.
     */
    private static Set<String> withoutBareDigits(Collection<String> chords) {
        return normalized(chords.stream().filter(chord -> !chord.matches("[0-9]")).toList());
    }

    private static Map<String, Set<String>> boundChordsByAction() {
        Map<String, Set<String>> bound = new TreeMap<>();
        for (String bindingName : ((DefaultBindings) KEYMAP.bindings()).actions()) {
            String actionId = actionIdOf(bindingName);
            bound.put(actionId, boundChordsOf(actionId));
        }
        return bound;
    }

    private static Map<String, Set<String>> actionsByChord() {
        Map<String, Set<String>> actionsByChord = new LinkedHashMap<>();
        BOUND_CHORDS.forEach((actionId, chords) -> {
            for (String chord : chords) {
                actionsByChord.computeIfAbsent(chord.toLowerCase(Locale.ROOT), key -> new TreeSet<>()).add(actionId);
            }
        });
        return actionsByChord;
    }

    private static String actionIdOf(String bindingName) {
        for (KeyAction action : KEYMAP.actions()) {
            if (Keymap.bindingName(action.id()).equals(bindingName)) {
                return action.id();
            }
        }
        return bindingName;
    }

    private static Set<String> boundChordsOf(String actionId) {
        Set<String> chords = new TreeSet<>();
        for (InputTrigger trigger : KEYMAP.bindings().triggersFor(Keymap.bindingName(actionId))) {
            chords.add(trigger.describe());
        }
        return chords;
    }

    private static Set<String> normalized(Collection<String> chords) {
        Set<String> normalized = new TreeSet<>();
        for (String chord : chords) {
            normalized.add(chord.toLowerCase(Locale.ROOT));
        }
        return normalized;
    }
}
