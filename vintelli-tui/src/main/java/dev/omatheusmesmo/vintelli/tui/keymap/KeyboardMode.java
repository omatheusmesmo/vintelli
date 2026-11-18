package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.terminal.KeyboardProtocol;

import java.util.Locale;

/**
 * Keyboard protocol the TUI shell runs, picked with the {@value #PROPERTY} system property. {@code auto} pushes
 * the Kitty keyboard protocol flags TamboUI 0.7 uses by default, so a Kitty-capable terminal delivers the
 * Ctrl+Shift chords, the keypad keys and the Ctrl+Arrow and Ctrl+Backspace chords as distinct keys, and every
 * other terminal ignores the push and behaves as {@code legacy}. {@code legacy} never pushes anything and keeps
 * the bytes and chords of a plain terminal. The full Kitty mode (key release events) is not offered.
 */
public enum KeyboardMode {

    AUTO(KeyboardProtocol.AUTO),
    LEGACY(KeyboardProtocol.LEGACY);

    /**
     * Mode used when the {@value #PROPERTY} property is unset.
     */
    public static final KeyboardMode DEFAULT = AUTO;

    /**
     * System property that selects the keyboard mode, {@code auto} or {@code legacy}, case-insensitive.
     */
    public static final String PROPERTY = "vintelli.keyboard.protocol";

    private final KeyboardProtocol protocol;

    KeyboardMode(KeyboardProtocol protocol) {
        this.protocol = protocol;
    }

    /**
     * TamboUI keyboard protocol the runner is configured with for this mode.
     */
    public KeyboardProtocol protocol() {
        return protocol;
    }

    /**
     * Name of the mode, also accepted by {@value #PROPERTY}.
     */
    public String displayName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a mode by its {@link #displayName()}, failing fast on an unknown name so a typo in
     * {@value #PROPERTY} never starts the shell with a keyboard the user did not ask for.
     */
    public static KeyboardMode fromName(String value) {
        for (KeyboardMode mode : values()) {
            if (mode.displayName().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "unknown keyboard protocol '" + value + "'; valid protocols: auto, legacy");
    }
}
