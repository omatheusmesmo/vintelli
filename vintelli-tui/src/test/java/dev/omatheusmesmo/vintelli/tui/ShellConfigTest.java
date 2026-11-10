package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShellConfigTest {

    @Test
    void ofFillsTheShortestShellDefaults() {
        List<ToolWindow> windows = List.of(new FakeToolWindow("run", "Run", 'r', 1, "run"));
        ShellState state = new ShellState();
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);

        ShellConfig config = ShellConfig.of(windows, state, themes);

        assertSame(windows, config.toolWindows());
        assertSame(state, config.state());
        assertSame(themes, config.themes());
        assertEquals(Keymap.defaultKeymap(windows).actions(), config.keymapSupplier().get().actions());
        assertNull(config.projectSearch());
        assertEquals(List.of(), config.navigationProviders());
        assertNull(config.capabilityRegistry());
        assertEquals(List.of(), config.vcsProviders());
        assertFalse(config.persistLayout());
        assertSame(windows, config.statusBarContributors());
        assertNull(config.fileOperations());
        assertNull(config.ideaMcpConnection());
        assertNull(config.layoutStore());
    }
}
