package dev.omatheusmesmo.vintelli.tui.keymap;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Resolution of the directory that holds the user shell configuration: the
 * user keymap file, the user theme file and the user file icon mapping. The
 * shared override {@code vintelli.config.dir} (system property, then the
 * {@code VINTELLI_CONFIG_DIR} environment variable, the same value
 * MicroProfile Config presents to the theme engine) is honored by every
 * consumer, so one override relocates all three kinds of configuration
 * together. The keymap keeps its more specific override
 * {@link #USER_KEYMAP_DIR_PROPERTY} ahead of it. Order overall: the keymap
 * override first, then the shared override, then {@code XDG_CONFIG_HOME}
 * (absolute on the XDG spec), then {@code APPDATA} on Windows, then the
 * {@code user.home} based default, which keeps {@code $HOME/.config} on Linux
 * and macOS. The {@link #configRoot} resolution is the single place all
 * consumers share.
 *
 * <p>Limitation: this resolver reads the shared override from the system
 * property and the {@code VINTELLI_CONFIG_DIR} environment variable only.
 * A value supplied through MicroProfile Config sources such as
 * {@code application.properties} or a {@code .env} file reaches the theme
 * engine (which reads {@code vintelli.config.dir} through
 * {@code @ConfigProperty}) but not the keymap or the file icon mapping that
 * go through this class.
 */
public final class ConfigDirectory {

    static final String USER_KEYMAP_DIR_PROPERTY = "vintelli.keymap.dir";
    public static final String CONFIG_DIR_PROPERTY = "vintelli.config.dir";
    static final String CONFIG_DIR_ENV = "VINTELLI_CONFIG_DIR";

    private ConfigDirectory() {
    }

    /**
     * The user keymap file resolved from the actual environment and system
     * properties, or null when no config directory can be derived: the shell
     * then runs on the profile bindings only.
     */
    static Path userKeymapFile() {
        return userKeymapFile(System.getenv(), System.getProperties());
    }

    static Path userKeymapFile(Map<String, String> env, Properties system) {
        Path configRoot = configRoot(keymapOverride(env, system),
                env.get("XDG_CONFIG_HOME"),
                env.get("APPDATA"),
                system.getProperty("user.home"));
        return configRoot == null ? null : configRoot.resolve("vintelli").resolve("keymap.properties");
    }

    /**
     * The shared config directory override from the actual environment and
     * system properties: the property first, then the environment variable,
     * and null when neither carries a value, so every consumer falls through
     * to the same XDG defaults.
     */
    public static String sharedConfigDir() {
        return sharedConfigDir(System.getenv(), System.getProperties());
    }

    static String sharedConfigDir(Map<String, String> env, Properties system) {
        String property = system.getProperty(CONFIG_DIR_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String fromEnv = env.get(CONFIG_DIR_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return null;
    }

    private static String keymapOverride(Map<String, String> env, Properties system) {
        String specific = system.getProperty(USER_KEYMAP_DIR_PROPERTY);
        if (specific != null && !specific.isBlank()) {
            return specific;
        }
        return sharedConfigDir(env, system);
    }

    public static Path configRoot(String propertyValue, String xdgConfigHome, String appData, String userHome) {
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Path.of(propertyValue);
        }
        if (xdgConfigHome != null && !xdgConfigHome.isBlank() && Path.of(xdgConfigHome).isAbsolute()) {
            return Path.of(xdgConfigHome);
        }
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData);
        }
        if (userHome != null && !userHome.isBlank()) {
            return Path.of(userHome, ".config");
        }
        return null;
    }
}
