package dev.omatheusmesmo.vintelli;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.cli.VintelliCliCommands;
import dev.omatheusmesmo.vintelli.cli.VintelliCliRepl;
import dev.omatheusmesmo.vintelli.launch.RunMode;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
public final class VintelliMain implements QuarkusApplication {

    private final Instance<VintelliShell> shell;
    private final Instance<KeymapRegistry> keymapRegistry;
    private final Instance<ShellThemes> themes;
    private final VintelliCliCommands cliCommands;
    private final VintelliCliRepl cliRepl;
    private final boolean fileLogging;
    private final Optional<String> logFile;

    @Inject
    VintelliMain(Instance<VintelliShell> shell, Instance<KeymapRegistry> keymapRegistry,
            Instance<ShellThemes> themes, VintelliCliCommands cliCommands, VintelliCliRepl cliRepl,
            @ConfigProperty(name = "quarkus.log.file.enabled", defaultValue = "false") boolean fileLogging,
            @ConfigProperty(name = "quarkus.log.file.path") Optional<String> logFile) {
        this.shell = shell;
        this.keymapRegistry = keymapRegistry;
        this.themes = themes;
        this.cliCommands = cliCommands;
        this.cliRepl = cliRepl;
        this.fileLogging = fileLogging;
        this.logFile = logFile;
    }

    static String tuiFailure(String message, boolean fileLogging, Optional<String> logFile) {
        String line = "Vintelli cannot start the TUI: " + message;
        if (fileLogging && logFile.isPresent()) {
            return line + System.lineSeparator() + "Details are in the log file: " + logFile.get();
        }
        return line;
    }

    @Override
    public int run(String... args) throws Exception {
        List<String> arguments = Arrays.asList(args);
        RunMode mode = RunMode.of(arguments);
        if (mode == RunMode.TUI) {
            try {
                ShellThemes shellThemes = themes.get();
                VintelliShell tuiShell = shell.get();
                Keymap keymap = keymapRegistry.get().get();
                try (ToolkitRunner runner = ToolkitRunner.builder()
                        .bindings(keymap.bindings())
                        .styleEngine(shellThemes.engine())
                        .mouseCapture(true)
                        .faultTolerant(true)
                        .build()) {
                    runner.eventRouter().addGlobalHandler(tuiShell);
                    tuiShell.bindQuit(runner::quit);
                    runner.run(tuiShell::root);
                }
                Quarkus.asyncExit(0);
                return 0;
            } catch (RuntimeException failure) {
                System.err.println(tuiFailure(failure.getMessage(), fileLogging, logFile));
                Quarkus.asyncExit(2);
                return 2;
            }
        }
        return switch (mode) {
            case REPL -> cliRepl.run();
            case CLI -> cliCommands.execute(arguments);
            default -> {
                Quarkus.waitForExit();
                yield 0;
            }
        };
    }
}
