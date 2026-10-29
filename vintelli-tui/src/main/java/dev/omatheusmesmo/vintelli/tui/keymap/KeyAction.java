package dev.omatheusmesmo.vintelli.tui.keymap;

/**
 * One named action of the keymap: an IntelliJ-style action id (for example
 * {@code ActivateRunToolWindow}), the human readable label shown in the
 * command palette and the keymap help overlay, and optionally the real
 * IntelliJ shortcut of the action in IntelliJ itself: the palette shows it
 * whenever the terminal cannot deliver that chord, so the palette stays the
 * fallback entry point. Empty when the action is Vintelli-specific or the
 * IntelliJ shortcut equals the active chord.
 *
 * @param id                IntelliJ-style action id, unique across the catalog
 * @param label             human readable title shown in the palette and help overlay
 * @param intellijShortcut  the shortcut IntelliJ itself binds, for example
 *                          {@code Double Shift}; empty when not applicable
 */
public record KeyAction(String id, String label, String intellijShortcut) {
}
