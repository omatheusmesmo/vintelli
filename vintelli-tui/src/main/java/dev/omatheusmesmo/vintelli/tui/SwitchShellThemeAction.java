package dev.omatheusmesmo.vintelli.tui;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * The named shell action that switches to the next theme at runtime. It is
 * the hook the keymap binds a key to (see issue #144): the shell never reads
 * keys for theme switching. Cycling follows the order the themes were
 * registered in, so the user theme file participates like any other theme.
 */
@Singleton
public final class SwitchShellThemeAction {

    static final String NAME = "shell.theme.switch";

    private final ShellThemes themes;

    @Inject
    public SwitchShellThemeAction(ShellThemes themes) {
        this.themes = themes;
    }

    public String name() {
        return NAME;
    }

    /**
     * Switches to the theme after the active one, cycling at the end of the
     * list. Fails only if the switch itself fails, which cannot happen for a
     * registered name.
     */
    public void perform() {
        List<String> names = themes.availableThemeNames();
        int index = names.indexOf(themes.activeTheme());
        themes.switchTheme(names.get(Math.floorMod(index + 1, names.size())));
    }
}
