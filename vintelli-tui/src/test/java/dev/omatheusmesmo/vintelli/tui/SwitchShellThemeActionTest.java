package dev.omatheusmesmo.vintelli.tui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwitchShellThemeActionTest {

    @Test
    void cyclesThroughTheRegisteredThemesAtRuntime() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        SwitchShellThemeAction action = new SwitchShellThemeAction(() -> themes);

        assertEquals(SwitchShellThemeAction.NAME, action.name());

        action.perform();
        assertEquals("darcula", themes.activeTheme());

        action.perform();
        assertEquals("dark", themes.activeTheme(), "the cycle wraps back to the default theme");
    }
}
