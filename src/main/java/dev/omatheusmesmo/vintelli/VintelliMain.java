package dev.omatheusmesmo.vintelli;

import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.inject.Singleton;

import java.util.Arrays;

@Singleton
public final class VintelliMain implements QuarkusApplication {

    private final VintelliShell shell;

    VintelliMain(VintelliShell shell) {
        this.shell = shell;
    }

    @Override
    public int run(String... args) throws Exception {
        boolean enableTui = Arrays.stream(args).anyMatch("--tui"::equals);
        if (enableTui) {
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
