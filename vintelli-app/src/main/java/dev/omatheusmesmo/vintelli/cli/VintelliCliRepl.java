package dev.omatheusmesmo.vintelli.cli;

import java.io.InputStream;
import java.io.OutputStream;
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
 * console with the exit command. When stdin is not a terminal, a piped
 * connection over the standard streams is used so the console stops cleanly on
 * {@code exit} even in a native image, where the system terminal pump does not
 * unblock, and every command finishes before the next line is read.
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

    /**
     * Runs the console on the given connection; a null connection opens the
     * system terminal on a TTY and a piped connection otherwise. The ready
     * callback fires once readline accepts input.
     */
    public int run(Connection connection, Runnable onReady) {
        Connection actual = connection;
        if (actual == null && !isInteractiveStdin()) {
            actual = pipedConnection(System.in, System.out);
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

    /**
     * Connection over piped standard streams that reports itself as not
     * interactive, on purpose: AEsh then runs every command of the console
     * synchronously, so a line read after it (such as {@code exit} piped right
     * behind {@code inspect}) waits until the command has printed everything.
     * An interactive connection runs each command on a daemon thread the
     * console does not wait for, and the application stops before a slow
     * command, such as a version control probe, prints its rows.
     */
    static Connection pipedConnection(InputStream input, OutputStream output) {
        return new PipedConnection(input, output);
    }

    private static final class PipedConnection extends StreamConnection {

        PipedConnection(InputStream input, OutputStream output) {
            super(StandardCharsets.UTF_8, input, output);
        }

        @Override
        public boolean isInteractive() {
            return false;
        }
    }
}
