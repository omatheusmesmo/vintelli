package dev.omatheusmesmo.vintelli.tui.keymap;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Resolution of the directory that holds the user keymap file. Order: the
 * {@code vintelli.keymap.dir} system property (absolute or relative, so tests
 * and users can point anywhere), then {@code XDG_CONFIG_HOME} (absolute on the
 * XDG spec), then {@code APPDATA} on Windows, then the {@code user.home} based
 * default, which keeps {@code $HOME/.config} on Linux and macOS. The
 * {@link #configRoot} resolution is shared with the file type icons, which
 * pass no property override of their own.
 */
public final class ConfigDirectory {

    static final String USER_KEYMAP_DIR_PROPERTY = "vintelli.keymap.dir";

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
        Path configRoot = configRoot(system.getProperty(USER_KEYMAP_DIR_PROPERTY),
                env.get("XDG_CONFIG_HOME"),
                env.get("APPDATA"),
                system.getProperty("user.home"));
        return configRoot == null ? null : configRoot.resolve("vintelli").resolve("keymap.properties");
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
