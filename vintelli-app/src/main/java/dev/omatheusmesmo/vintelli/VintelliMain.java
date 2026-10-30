package dev.omatheusmesmo.vintelli;

import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapRegistry;
import dev.tamboui.toolkit.app.ToolkitRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Singleton;

import java.util.Arrays;

@Singleton
public final class VintelliMain implements QuarkusApplication {

    private final Instance<VintelliShell> shell;
    private final Instance<KeymapRegistry> keymapRegistry;
    private final Instance<ShellThemes> themes;

    VintelliMain(Instance<VintelliShell> shell, Instance<KeymapRegistry> keymapRegistry, Instance<ShellThemes> themes) {
        this.shell = shell;
        this.keymapRegistry = keymapRegistry;
        this.themes = themes;
    }

    /**
     * TUI mode resolves the shell, the keymap and the theme engine only inside
     * the guarded block, so a bad theme name or a malformed user theme file
     * fails there with the one line message and exit code 2; headless and
     * MCP-only runs resolve none of it and never touch a theme.
     */
    @Override
    public int run(String... args) throws Exception {
        boolean enableTui = Arrays.stream(args).anyMatch("--tui"::equals);
        if (enableTui) {
            try {
                ShellThemes shellThemes = themes.get();
                VintelliShell tuiShell = shell.get();
                Keymap keymap = keymapRegistry.get().get();
                try (ToolkitRunner runner = ToolkitRunner.builder()
                        .bindings(keymap.bindings())
                        .styleEngine(shellThemes.engine())
                        .faultTolerant(true)
                        .build()) {
                    runner.eventRouter().addGlobalHandler(tuiShell);
                    tuiShell.bindQuit(runner::quit);
                    runner.run(tuiShell::root);
                }
                Quarkus.asyncExit(0);
                return 0;
            } catch (RuntimeException failure) {
                System.err.println("Vintelli cannot start the TUI: " + failure.getMessage());
                Quarkus.asyncExit(2);
                return 2;
            }
        }
        Quarkus.waitForExit();
        return 0;
    }
}
