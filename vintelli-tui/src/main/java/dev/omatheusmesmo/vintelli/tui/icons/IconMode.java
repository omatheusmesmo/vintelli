package dev.omatheusmesmo.vintelli.tui.icons;

import java.util.Locale;
import java.util.Map;

/**
 * Icon face the Vintelli TUI renders: Nerd Font glyphs from a patched font, or
 * pure ASCII markers with color only.
 *
 * <p>Chosen by the {@link #CONFIG_PROPERTY} system property and, when the value
 * is {@code auto}, by the terminal capability. The font of the terminal is not
 * observable from the process, so the auto detection trusts only the terminals
 * that bundle the Nerd Font symbol glyphs by default (kitty, Ghostty and
 * WezTerm); every other terminal (JetBrains IDE terminal, VS Code, iTerm2,
 * Windows Terminal, plain {@code xterm} via SSH and so on) gets the ASCII face
 * unless the user opts in with {@code vintelli.icons=nerd}. The reasons are
 * documented in the README: on a terminal without the patched font, glyphs from
 * the private use area render as tofu boxes, empty cells or overlapping
 * characters whose advance width varies per terminal, while ASCII markers and
 * colors stay readable everywhere.
 */
public enum IconMode {

    /**
     * Nerd Font glyphs: for terminals with a patched font or one that bundles
     * the Nerd Font symbols.
     */
    NERD("nerd"),

    /**
     * Pure ASCII markers, colored only: for terminals without the patched font
     * or when the user prefers exact terminal compatibility.
     */
    ASCII("ascii"),

    /**
     * Detect the face from the terminal environment.
     */
    AUTO("auto");

    /**
     * System property that selects the face: {@code auto} (default), {@code nerd}
     * or {@code ascii}.
     */
    public static final String CONFIG_PROPERTY = "vintelli.icons";

    private final String configValue;

    IconMode(String configValue) {
        this.configValue = configValue;
    }

    /**
     * The face for the configuration value, or {@code auto} when it is absent.
     * Unknown values fail fast naming the property and the allowed values.
     */
    public static IconMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (IconMode mode : values()) {
            if (mode.configValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown " + CONFIG_PROPERTY + " value '" + value
                + "'; expected one of: auto, nerd, ascii");
    }

    /**
     * The face that actually renders: {@code auto} asks the terminal
     * environment, the explicit values stay as configured.
     */
    public IconMode resolve(Map<String, String> env) {
        return this == AUTO ? autoDetect(env) : this;
    }

    private static IconMode autoDetect(Map<String, String> env) {
        if (present(env, "KITTY_PID")
                || present(env, "KITTY_WINDOW_ID")
                || present(env, "GHOSTTY_RESOURCES_DIR")
                || present(env, "WEZTERM_EXECUTABLE")
                || "WezTerm".equalsIgnoreCase(env.get("TERM_PROGRAM"))) {
            return NERD;
        }
        return ASCII;
    }

    private static boolean present(Map<String, String> env, String key) {
        String value = env.get(key);
        return value != null && !value.isBlank();
    }
}
