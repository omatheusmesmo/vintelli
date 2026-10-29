package dev.omatheusmesmo.vintelli.tui.keymap;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigDirectoryTest {

    private static final Path SHARED = Path.of("shared-config");
    private static final Path KEYMAP = Path.of("keymap-config");

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
