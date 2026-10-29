package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;

/**
 * Shipped keymap profiles. A profile defines the base bindings of every named
 * action on top of the TamboUI binding sets; the shell never knows which
 * profile is active, so switching profiles is data only.
 *
 * <p>{@code intellij} is the default and mirrors the reachable subset of the
 * IntelliJ shortcuts (see the keymap section of the README for the chords the
 * terminal cannot deliver, for example Double Shift and Ctrl+Shift+letter).
 * {@code vim} swaps the shell navigation to hjkl through the TamboUI vim
 * binding set.
 */
public enum KeymapProfile {

    INTELLIJ("intellij"),
    VIM("vim");

    /**
     * Profile used when the {@code vintelli.keymap} property is unset.
     */
    public static final KeymapProfile DEFAULT = INTELLIJ;

    private final String displayName;

    KeymapProfile(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Name of the profile, also accepted by {@code vintelli.keymap}.
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Base binding set of the profile, before the shell-level actions and the
     * tool window activations are bound on top.
     */
    Bindings navigation() {
        return switch (this) {
            case INTELLIJ -> BindingSets.intellij();
            case VIM -> BindingSets.vim();
        };
    }

    /**
     * Resolves a profile by its {@link #displayName()}, failing fast on an
     * unknown name so a typo in {@code vintelli.keymap} never starts half a
     * keymap.
     */
    public static KeymapProfile fromName(String value) {
        for (KeymapProfile profile : values()) {
            if (profile.displayName.equalsIgnoreCase(value)) {
                return profile;
            }
        }
        throw new IllegalArgumentException(
                "unknown keymap profile '" + value + "'; valid profiles: intellij, vim");
    }
}
