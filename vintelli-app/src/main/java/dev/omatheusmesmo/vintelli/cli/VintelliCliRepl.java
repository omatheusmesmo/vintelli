package dev.omatheusmesmo.vintelli.cli;

import java.nio.charset.StandardCharsets;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.aesh.AeshConsoleRunner;
import org.aesh.terminal.Connection;
import org.aesh.terminal.StreamConnection;
import org.aesh.terminal.tty.TtyDetect;

/**
 * The REPL surface of the AEsh command layer, driven by {@code --repl}: the
 * same command registry as the one-shot mode, wrapped in an interactive
 * console with the exit command. When stdin is not a terminal, a
 * {@link StreamConnection} over the standard streams is used so the console
 * stops cleanly on {@code exit} even in a native image, where the system
 * terminal pump does not unblock.
 */
@Singleton
public final class VintelliCliRepl {

    private final VintelliCliCommands commands;

    @Inject
    VintelliCliRepl(VintelliCliCommands commands) {
        this.commands = commands;
    }

    /**
     * Runs the blocking console until the user exits.
     *
     * @return the console exit code, always the success code today
     */
    public int run() {
        return run(null, () -> {
        });
    }

    /**
     * Runs the console on the given connection; a null connection opens the
     * system terminal on a TTY and a {@link StreamConnection} otherwise. The
     * ready callback fires once readline accepts input.
     */
    private static boolean isInteractiveStdin() {
        if (System.console() == null) {
            return false;
        }
        try {
            return TtyDetect.isStdinTty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public int run(Connection connection, Runnable onReady) {
        Connection actual = connection;
        if (actual == null && !isInteractiveStdin()) {
            actual = new StreamConnection(StandardCharsets.UTF_8, System.in, System.out);
        }
        AeshConsoleRunner.builder()
                .connection(actual)
                .commandRegistryBuilder(commands.registryBuilder())
                .addExitCommand()
                .prompt("vintelli> ")
                .onReady(onReady)
                .start();
        return 0;
    }
}
