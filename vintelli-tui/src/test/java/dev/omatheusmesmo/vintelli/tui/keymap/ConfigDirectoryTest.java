package dev.omatheusmesmo.vintelli.tui.keymap;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDirectoryTest {

    private static final Path SHARED = Path.of("shared-config");
    private static final Path KEYMAP = Path.of("keymap-config");

    @Test
    void theBuildPointsTheSharedConfigDirInsideTheModuleBuildOutput() {
        String configured = ConfigDirectory.sharedConfigDir();

        assertNotNull(configured, "tests must not fall through to the user's real configuration directory");
        assertTrue(Path.of(configured).toAbsolutePath().normalize()
                .startsWith(Path.of("target").toAbsolutePath().normalize()));
    }

    @Test
    void sharedConfigDirOverrideRelocatesTheUserKeymap() {
        Properties system = new Properties();
        system.setProperty(ConfigDirectory.CONFIG_DIR_PROPERTY, SHARED.toString());

        assertEquals(SHARED.resolve("vintelli").resolve("keymap.properties"),
                ConfigDirectory.userKeymapFile(Map.of(), system));
    }

    @Test
    void sharedConfigDirEnvironmentVariableRelocatesTheUserKeymap() {
        assertEquals(SHARED.resolve("vintelli").resolve("keymap.properties"),
                ConfigDirectory.userKeymapFile(Map.of(ConfigDirectory.CONFIG_DIR_ENV, SHARED.toString()), new Properties()));
    }

    @Test
    void theKeymapSpecificOverrideWinsOverTheSharedConfigDir() {
        Properties system = new Properties();
        system.setProperty(ConfigDirectory.CONFIG_DIR_PROPERTY, SHARED.toString());
        system.setProperty("vintelli.keymap.dir", KEYMAP.toString());

        assertEquals(KEYMAP.resolve("vintelli").resolve("keymap.properties"),
                ConfigDirectory.userKeymapFile(Map.of(), system));
    }
}
