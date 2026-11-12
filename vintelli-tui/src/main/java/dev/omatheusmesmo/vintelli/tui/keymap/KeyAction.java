package dev.omatheusmesmo.vintelli.tui.keymap;

/**
 * One named action of the keymap: an IntelliJ-style action id (for example
 * {@code ActivateRunToolWindow}) and the human readable label shown in the
 * command palette and the keymap help overlay. The IntelliJ chord of the
 * action is not stored here: it is the row of the comparison table
 * ({@code intellij-keymap.csv}) that the palette and the help read.
 *
 * @param id     IntelliJ-style action id, unique across the catalog
 * @param label  human readable title shown in the palette and help overlay
 */
public record KeyAction(String id, String label) {
}
