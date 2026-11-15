package dev.omatheusmesmo.vintelli.tui.icons;

import java.nio.file.Path;
import java.util.Locale;

import dev.omatheusmesmo.vintelli.tui.keymap.ConfigDirectory;

/**
 * Resolves {@link FileIcons} for a test with an explicit face and an empty config
 * directory, so the result never depends on the terminal of the machine running the
 * test or on the user mapping file of the developer.
 */
public final class FileIconsFixture {

    private FileIconsFixture() {
    }

    public static FileIcons resolved(IconMode mode, Path configDirectory) {
        if (mode == IconMode.AUTO) {
            throw new IllegalArgumentException("a test must name the icon face explicitly");
        }
        String previousMode = System.getProperty(IconMode.CONFIG_PROPERTY);
        String previousDirectory = System.getProperty(ConfigDirectory.CONFIG_DIR_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, mode.name().toLowerCase(Locale.ROOT));
        System.setProperty(ConfigDirectory.CONFIG_DIR_PROPERTY, configDirectory.toString());
        try {
            return FileIcons.resolve();
        } finally {
            restore(IconMode.CONFIG_PROPERTY, previousMode);
            restore(ConfigDirectory.CONFIG_DIR_PROPERTY, previousDirectory);
        }
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
