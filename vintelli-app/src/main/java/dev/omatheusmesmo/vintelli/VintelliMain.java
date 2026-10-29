package dev.omatheusmesmo.vintelli;

import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapRegistry;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.inject.Singleton;

import java.util.Arrays;

@Singleton
public final class VintelliMain implements QuarkusApplication {

    private final VintelliShell shell;
    private final KeymapRegistry keymapRegistry;

    VintelliMain(VintelliShell shell, KeymapRegistry keymapRegistry) {
        this.shell = shell;
        this.keymapRegistry = keymapRegistry;
    }

    @Override
    public int run(String... args) throws Exception {
        boolean enableTui = Arrays.stream(args).anyMatch("--tui"::equals);
        if (enableTui) {
            try {
                keymapRegistry.get();
            } catch (RuntimeException failure) {
                System.err.println("Vintelli cannot start the TUI: " + failure.getMessage());
                Quarkus.asyncExit(2);
                return 2;
            }
            try (TuiRunner tui = TuiRunner.create(TuiConfig.defaults())) {
                tui.run(shell, shell::render);
            }
            Quarkus.asyncExit(0);
            return 0;
        }
        Quarkus.waitForExit();
        return 0;
    }
}
