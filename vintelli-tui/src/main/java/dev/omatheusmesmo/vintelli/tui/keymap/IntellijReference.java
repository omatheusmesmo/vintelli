package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.bindings.InputTrigger;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The IntelliJ reference of every catalog action, read from the comparison
 * table {@value KeymapTable#RESOURCE}, which is the one source of the palette
 * hint and the keymap help column. The palette shows the IntelliJ chord only
 * while the chord is one a terminal can send and differs from the chords bound
 * now; the help shows it for every action that has one.
 */
final class IntellijReference {

    private static final KeymapTable TABLE = KeymapTable.load();
    private static final String NOT_SENT_BY_TERMINALS = ", not sent by terminals";

    private IntellijReference() {
    }

    /**
     * Hint the palette shows next to the active chord, for example
     * {@code (IntelliJ: Alt+F12)}; empty when there is no IntelliJ chord, the
     * terminal cannot send it, or the chords bound now already match it.
     */
    static String paletteHint(Keymap keymap, String actionId) {
        KeymapTable.Row row = TABLE.rowOf(actionId);
        if (row == null || row.intellijChords().isEmpty()
                || row.status() == KeymapTable.Status.NOT_DELIVERABLE) {
            return "";
        }
        if (comparable(row.intellijChords()).equals(comparable(activeChords(keymap, actionId)))) {
            return "";
        }
        return intellijText(row, "");
    }

    /**
     * Text of the IntelliJ column of the keymap help, for example
     * {@code (IntelliJ: Alt+4)} or {@code (IntelliJ: Double Shift, not sent by
     * terminals)}; empty when the action has no IntelliJ chord.
     */
    static String helpText(String actionId) {
        KeymapTable.Row row = TABLE.rowOf(actionId);
        if (row == null || row.intellijChords().isEmpty()) {
            return "";
        }
        String note = row.status() == KeymapTable.Status.NOT_DELIVERABLE ? NOT_SENT_BY_TERMINALS : "";
        return intellijText(row, note);
    }

    private static String intellijText(KeymapTable.Row row, String note) {
        return "(IntelliJ: " + String.join(" | ", row.intellijChords()) + note + ")";
    }

    /**
     * The chords the action has now, without the bare digit of a tool window
     * (the shortcut for while no input has focus, not an IntelliJ chord).
     */
    private static Set<String> activeChords(Keymap keymap, String actionId) {
        Set<String> chords = new TreeSet<>();
        for (InputTrigger trigger : keymap.bindings().triggersFor(Keymap.bindingName(actionId))) {
            String chord = trigger.describe();
            if (!chord.matches("[0-9]")) {
                chords.add(chord);
            }
        }
        return comparable(chords);
    }

    private static Set<String> comparable(Iterable<String> chords) {
        Set<String> normalized = new TreeSet<>();
        for (String chord : chords) {
            normalized.add(chord.toLowerCase(Locale.ROOT));
        }
        return normalized;
    }
}
