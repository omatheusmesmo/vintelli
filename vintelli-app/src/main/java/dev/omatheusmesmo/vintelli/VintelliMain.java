package dev.omatheusmesmo.vintelli;

import java.util.Arrays;

import dev.omatheusmesmo.vintelli.cli.VintelliCliCommands;
import dev.omatheusmesmo.vintelli.cli.VintelliCliRepl;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapRegistry;
import dev.tamboui.toolkit.app.ToolkitRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public final class VintelliMain implements QuarkusApplication {

    static final String TUI_FLAG = "--tui";
    static final String REPL_FLAG = "--repl";

    private final Instance<VintelliShell> shell;
    private final Instance<KeymapRegistry> keymapRegistry;
    private final Instance<ShellThemes> themes;
    private final VintelliCliCommands cliCommands;
    private final VintelliCliRepl cliRepl;

    @Inject
    VintelliMain(Instance<VintelliShell> shell, Instance<KeymapRegistry> keymapRegistry,
            Instance<ShellThemes> themes, VintelliCliCommands cliCommands, VintelliCliRepl cliRepl) {
        this.shell = shell;
        this.keymapRegistry = keymapRegistry;
        this.themes = themes;
        this.cliCommands = cliCommands;
        this.cliRepl = cliRepl;
    }

    @Override
    public int run(String... args) throws Exception {
        if (Arrays.asList(args).contains(TUI_FLAG)) {
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
        if (args.length > 0 && REPL_FLAG.equals(args[0])) {
            return cliRepl.run();
        }
        if (args.length > 0) {
            return cliCommands.execute(Arrays.asList(args));
        }
        Quarkus.waitForExit();
        return 0;
    }
}
