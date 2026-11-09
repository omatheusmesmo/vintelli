package dev.omatheusmesmo.vintelli.tui.terminal;

import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.style.Color.Rgb;
import dev.tamboui.terminal.Backend;
import org.jboss.logging.Logger;

import java.io.Console;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Paints the terminal's own default background with the frame tone of the
 * active theme while the TUI runs, so the padding and margin the terminal
 * draws around the grid match the shell. The default is reset with OSC 111
 * when the TUI stops, and again on JVM shutdown, so a crash or SIGTERM does not
 * leave the terminal in the theme color.
 *
 * <p>The sequences go through the backend the TUI draws with, never through
 * stdout. Nothing is written unless the terminal is attached, its name is
 * trusted and the session is local, see {@link #attach}. The tone follows a
 * theme switch while the TUI runs. A write that the terminal does not take is
 * logged and ignored, because the margin is a presentation detail.
 */
public final class TerminalBackground implements AutoCloseable {

    public static final String CONFIG_PROPERTY = "vintelli.tui.terminal-background";
    private static final String RECORD_PROPERTY = "tamboui.record";

    static final String SET_PREFIX = "\u001b]11;#";
    static final String RESTORE_SEQUENCE = "\u001b]111\u0007";
    private static final String BEL = "\u0007";
    private static final Set<String> TRUSTED_TERMINALS = Set.of("alacritty", "foot", "ghostty", "kitty", "wezterm", "xterm");
    private static final List<String> TRUSTED_MARKERS = List.of("KITTY_WINDOW_ID", "GHOSTTY_RESOURCES_DIR", "WEZTERM_EXECUTABLE");
    private static final Logger LOG = Logger.getLogger(TerminalBackground.class);

    private final Output output;
    private final ShellThemes themes;
    private final Thread shutdownHook = new Thread(this::restore, "vintelli-terminal-background-restore");
    private Rgb applied;
    private boolean closed;

    /**
     * The channel the sequences are written to. A failing write throws, and the
     * caller decides how to report it.
     */
    @FunctionalInterface
    public interface Output {

        void write(String sequence) throws IOException;
    }

    /**
     * Sets the terminal background to the frame tone of the active theme when
     * the terminal qualifies, and returns the handle that restores it. Empty when
     * the setting is off, no terminal is attached, the terminal name is not
     * trusted, or the session is remote or basic: nothing is written then.
     */
    public static Optional<TerminalBackground> attach(Output output, ShellThemes themes, boolean configured,
            Map<String, String> env, boolean terminalAttached) {
        if (!configured || !terminalAttached || TerminalTrust.remoteSession(env)
                || TerminalTrust.basicTerminal(env) || !trustedTerminal(env)) {
            return Optional.empty();
        }
        return Optional.of(open(output, themes));
    }

    public static Output channel(Backend backend) {
        return sequence -> {
            backend.writeRaw(sequence);
            backend.flush();
        };
    }

    public static boolean consoleAttached() {
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    /**
     * A tape playback records the session through the real backend, so the
     * sequences are written as they would be to a terminal and the recording
     * can prove them.
     */
    public static boolean recordedSession() {
        return System.getProperty(RECORD_PROPERTY) != null;
    }

    static TerminalBackground open(Output output, ShellThemes themes) {
        TerminalBackground background = new TerminalBackground(output, themes);
        background.apply();
        themes.onSwitch(background::apply);
        Runtime.getRuntime().addShutdownHook(background.shutdownHook);
        return background;
    }

    private TerminalBackground(Output output, ShellThemes themes) {
        this.output = output;
        this.themes = themes;
    }

    /**
     * Resets the terminal default to the one it had before. Safe to call more
     * than once: only the first call after a set writes anything.
     */
    public synchronized void restore() {
        closed = true;
        if (applied != null) {
            applied = null;
            write(RESTORE_SEQUENCE);
        }
    }

    @Override
    public void close() {
        restore();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException jvmShuttingDown) {
            LOG.debug("the JVM is shutting down, its hook restores the terminal");
        }
    }

    private synchronized void apply() {
        if (closed) {
            return;
        }
        Rgb tone = themes.frameTone();
        if (tone.equals(applied)) {
            return;
        }
        applied = tone;
        write(SET_PREFIX + hex(tone) + BEL);
    }

    private void write(String sequence) {
        try {
            output.write(sequence);
        } catch (IOException failure) {
            LOG.debug("the terminal did not take a background sequence", failure);
        }
    }

    static String hex(Rgb rgb) {
        return String.format(Locale.ROOT, "%02x%02x%02x", rgb.r(), rgb.g(), rgb.b());
    }

    private static boolean trustedTerminal(Map<String, String> env) {
        String family = env.getOrDefault("TERM", "").split("-", 2)[0].toLowerCase(Locale.ROOT);
        String program = env.getOrDefault("TERM_PROGRAM", "").toLowerCase(Locale.ROOT);
        return TRUSTED_TERMINALS.contains(family)
                || TRUSTED_TERMINALS.contains(program)
                || TRUSTED_MARKERS.stream().anyMatch(marker -> TerminalTrust.present(env, marker));
    }
}
