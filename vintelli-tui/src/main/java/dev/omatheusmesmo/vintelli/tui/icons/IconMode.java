package dev.omatheusmesmo.vintelli.tui.icons;

import java.io.Console;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Icon face the Vintelli TUI renders: Nerd Font glyphs from a patched font, or
 * pure ASCII markers with color only.
 *
 * <p>Chosen by the {@link #CONFIG_PROPERTY} system property and, when the value
 * is {@code auto}, by the terminal and the machine. {@code auto} takes the first
 * rule that matches:
 * <ol>
 * <li>the terminal is kitty, Ghostty or WezTerm (by name or by its environment
 * marker): Nerd;</li>
 * <li>no terminal is attached to the process: ASCII;</li>
 * <li>the session is remote ({@code SSH_CONNECTION}, {@code SSH_CLIENT} or
 * {@code SSH_TTY} is set): ASCII;</li>
 * <li>{@code TERM} is {@code dumb} or {@code linux}: ASCII;</li>
 * <li>a Nerd Font file is installed in the font directories of the machine:
 * Nerd;</li>
 * <li>otherwise: ASCII.</li>
 * </ol>
 * The font of the terminal is not observable from the process, so rule 5 shows
 * only that the machine has a Nerd Font. A terminal that does not fall back to
 * installed fonts may still draw boxes or blanks; {@code vintelli.icons=ascii}
 * is the way out. The reasons are documented in docs/file-icons.md.
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
     * Detect the face from the terminal environment and the installed fonts.
     */
    AUTO("auto");

    /**
     * System property that selects the face: {@code auto} (default), {@code nerd}
     * or {@code ascii}.
     */
    public static final String CONFIG_PROPERTY = "vintelli.icons";

    private static final Set<String> TRUSTED_TERMINALS = Set.of("kitty", "ghostty", "wezterm");

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
     * The face that actually renders, with the terminal and the machine read from
     * this process: {@code auto} applies the rules of this class, the explicit
     * values stay as configured.
     */
    public IconMode resolve(Map<String, String> env) {
        return resolve(IconMode.Surroundings.ofThisProcess(env, null));
    }

    /**
     * The face that actually renders for the given surroundings. This is the only
     * place where the {@code auto} rules live.
     */
    IconMode resolve(Surroundings surroundings) {
        if (this != AUTO) {
            return this;
        }
        Map<String, String> env = surroundings.env();
        if (trustedTerminal(surroundings.terminalName(), env)) {
            return NERD;
        }
        if (!surroundings.terminalAttached() || remoteSession(env) || basicTerminal(env)) {
            return ASCII;
        }
        return NerdFontProbe.installed(env, surroundings.osName(), surroundings.home()) ? NERD : ASCII;
    }

    private static boolean trustedTerminal(String terminalName, Map<String, String> env) {
        String name = terminalName == null ? "" : terminalName.trim().toLowerCase(Locale.ROOT);
        return TRUSTED_TERMINALS.contains(name)
                || present(env, "KITTY_PID")
                || present(env, "KITTY_WINDOW_ID")
                || present(env, "GHOSTTY_RESOURCES_DIR")
                || present(env, "WEZTERM_EXECUTABLE")
                || "WezTerm".equalsIgnoreCase(env.get("TERM_PROGRAM"));
    }

    private static boolean remoteSession(Map<String, String> env) {
        return present(env, "SSH_CONNECTION") || present(env, "SSH_CLIENT") || present(env, "SSH_TTY");
    }

    private static boolean basicTerminal(Map<String, String> env) {
        String term = env.get("TERM");
        return "dumb".equalsIgnoreCase(term) || "linux".equalsIgnoreCase(term);
    }

    private static boolean present(Map<String, String> env, String key) {
        String value = env.get(key);
        return value != null && !value.isBlank();
    }

    /**
     * What {@code auto} looks at: the environment, the terminal name reported by
     * AEsh (null when not asked for), whether a terminal is attached, the
     * operating system name and the home directory. {@link #ofThisProcess} is the
     * only place that reads the console and the system properties.
     */
    record Surroundings(Map<String, String> env, String terminalName, boolean terminalAttached,
            String osName, String home) {

        static Surroundings ofThisProcess(Map<String, String> env, String terminalName) {
            Console console = System.console();
            return new Surroundings(env, terminalName, console != null && console.isTerminal(),
                    System.getProperty("os.name"), System.getProperty("user.home"));
        }
    }
}
