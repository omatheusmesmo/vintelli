package dev.omatheusmesmo.vintelli.tui;

import java.util.Locale;
import java.util.Map;

/**
 * Color depth the shell renders with: the theme hex values as 24-bit
 * truecolor, or the nearest fixed 256 color index of each of them.
 *
 * <p>Selected by the {@link #CONFIG_PROPERTY} property and, when the value is
 * {@code auto}, by the terminal environment. TamboUI never downgrades on its
 * own: a hex value is emitted as {@code 38;2;r;g;b} and an index as
 * {@code 38;5;n}, and nothing in the library reads {@code COLORTERM} (spike
 * {@code docs/spikes/spike-27-keyboard-and-colors.md}), so the shell picks the
 * representation before the styles are resolved.
 *
 * <p>In {@code auto} only {@code COLORTERM} is trusted, and only when it
 * contains {@code truecolor} or {@code 24bit} (case insensitive). Every other
 * value, including an unset variable, renders 256 colors. {@code TERM} is not
 * probed and the multiplexer is not asked: a missed truecolor terminal stays
 * readable, a truecolor value on a 256 color terminal does not.
 *
 * <p>The depth is picked outside this type: the owner of the themes reads the
 * configuration property and the terminal variable and hands both to
 * {@link #of(String, Map)}, so this type stays free of system state.
 */
public enum ColorLevel {

    /**
     * 24-bit color: the theme hex values reach the terminal unchanged.
     */
    TRUECOLOR("truecolor"),

    /**
     * 256 color mode: every theme hex is replaced by the nearest index.
     */
    INDEXED_256("256"),

    /**
     * Detect the depth from the terminal environment.
     */
    AUTO("auto");

    /**
     * Property that selects the depth: {@code auto} (default),
     * {@code truecolor} or {@code 256}.
     */
    public static final String CONFIG_PROPERTY = "vintelli.tui.color-level";

    /**
     * Environment variable a 24-bit terminal advertises its depth in. It is
     * read as a configuration property, which MicroProfile Config exposes the
     * environment variable under, and only consulted for {@link #AUTO}.
     */
    public static final String COLORTERM = "COLORTERM";

    private static final String COLORTERM_24BIT = "24bit";

    private final String configValue;

    ColorLevel(String configValue) {
        this.configValue = configValue;
    }

    /**
     * The resolved depth for an explicit configuration value and environment;
     * the entry point for the code that owns a theme, which passes the
     * configured value and the terminal variable it has read.
     */
    public static ColorLevel of(String configValue, Map<String, String> env) {
        return fromConfig(configValue).resolve(env);
    }

    /**
     * The depth for the configuration value, or {@code auto} when it is absent.
     * Unknown values fail fast naming the property and the allowed values.
     */
    public static ColorLevel fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ColorLevel level : values()) {
            if (level.configValue.equals(normalized)) {
                return level;
            }
        }
        throw new IllegalArgumentException("unknown " + CONFIG_PROPERTY + " value '" + value
                + "'; expected one of: auto, truecolor, 256");
    }

    /**
     * The depth that actually renders: {@code auto} asks the terminal
     * environment, the explicit values stay as configured.
     */
    public ColorLevel resolve(Map<String, String> env) {
        return this == AUTO ? autoDetect(env) : this;
    }

    private static ColorLevel autoDetect(Map<String, String> env) {
        String colorterm = env.get(COLORTERM);
        if (colorterm == null) {
            return INDEXED_256;
        }
        String advertised = colorterm.toLowerCase(Locale.ROOT);
        return advertised.contains(TRUECOLOR.configValue) || advertised.contains(COLORTERM_24BIT)
                ? TRUECOLOR
                : INDEXED_256;
    }
}
