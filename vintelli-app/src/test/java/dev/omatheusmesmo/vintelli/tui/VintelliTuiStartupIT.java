package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Starts the packaged runner (the Mandrel image under {@code -Dnative}, the
 * runnable jar otherwise) with no arguments and with the {@code --tui} alias,
 * and drives it headlessly through
 * TamboUI's Recording and Playback: a VHS tape waits for the tool window tabs and then
 * quits, and the frames are written to an asciinema cast. TamboUI resolves its
 * terminal backend through the ServiceLoader, which the native build disables,
 * so a missing provider registration shows at startup as
 * {@code none could be initialized}. Recording wraps the backend the provider
 * created, so that path is still exercised, and no TTY is needed.
 */
@QuarkusIntegrationTest
class VintelliTuiStartupIT {

    private static final long EXIT_TIMEOUT_SECONDS = 120;
    private static final long RECORD_DURATION_MILLIS = 100_000;
    private static final String TOOL_WINDOW_MARKER = "Terminal";
    private static final String TAPE = """
            Wait@60s /Terminal/
            Sleep 500ms
            Ctrl+C
            """;
    private static final Pattern NERD_GLYPH = Pattern.compile("[\\uE000-\\uF8FF]|\\\\u[eEfF][0-9a-fA-F]{3}");
    private static final String OSC_SET = "\u001b]11;#";
    private static final String OSC_RESET = "\u001b]111";
    private static final List<String> TERMINAL_ENVIRONMENT = List.of("TERM", "TERM_PROGRAM", "SSH_CONNECTION", "SSH_CLIENT",
            "SSH_TTY", "KITTY_WINDOW_ID", "GHOSTTY_RESOURCES_DIR", "WEZTERM_EXECUTABLE");
    private static final List<String> BACKEND_FAILURES = List.of(
            "BackendProvider", "none could be initialized", "cannot start the TUI");

    @TempDir
    Path workDir;

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void theTuiStartsAndDrawsItsToolWindowsThroughTapePlayback(boolean explicitTui) throws Exception {
        Recorded recorded = record(Map.of(), explicitTui, "startup");
        for (String failure : BACKEND_FAILURES) {
            assertFalse(recorded.report().contains(failure), "The TUI reported a backend failure\n" + recorded.report());
        }
        assertTrue(recorded.exited(), "The TUI did not exit within " + EXIT_TIMEOUT_SECONDS + "s\n" + recorded.report());
        assertTrue(recorded.castExists(), "No cast was recorded\n" + recorded.report());
        assertTrue(recorded.cast().contains(TOOL_WINDOW_MARKER), "The TUI never drew its tool windows\n" + recorded.report());
    }

    @ParameterizedTest
    @CsvSource({ "foot,1", "vt100,0" })
    void theTerminalBackgroundIsSetAndResetOnceOnATrustedTerminalOnly(String term, int expected) throws Exception {
        Recorded recorded = record(Map.of("TERM", term), false, "background-" + term);
        assertTrue(recorded.exited(), "The TUI did not exit within " + EXIT_TIMEOUT_SECONDS + "s\n" + recorded.report());
        assertEquals(expected, occurrences(recorded.stdout(), OSC_SET), "set sequences for TERM=" + term + "\n" + recorded.report());
        assertEquals(expected, occurrences(recorded.stdout(), OSC_RESET), "reset sequences for TERM=" + term + "\n" + recorded.report());
    }

    /**
     * The {@code auto} icon face reads the terminal of the running process. A
     * native image that evaluated that detection while it was built kept the
     * environment of the build machine and drew ASCII on every terminal, which
     * no JVM test can show. A terminal marker the image never saw must switch
     * the face to Nerd glyphs, and a basic terminal must keep ASCII.
     */
    @ParameterizedTest
    @CsvSource({ "KITTY_WINDOW_ID,1,true", "GHOSTTY_RESOURCES_DIR,/tmp,true", "TERM,dumb,false" })
    void theIconFaceFollowsTheTerminalOfTheRunningProcess(String variable, String value, boolean nerd) throws Exception {
        Recorded recorded = record(Map.of(variable, value), false, "icons-" + variable);
        assertTrue(recorded.exited(), "The TUI did not exit within " + EXIT_TIMEOUT_SECONDS + "s\n" + recorded.report());
        assertTrue(recorded.cast().contains(TOOL_WINDOW_MARKER), "The TUI never drew its tool windows\n" + recorded.report());
        assertEquals(nerd, drawsNerdGlyphs(recorded.cast()), "Nerd glyphs with " + variable + "=" + value + "\n" + recorded.report());
    }

    private static boolean drawsNerdGlyphs(String cast) {
        return NERD_GLYPH.matcher(cast).find();
    }

    private record Recorded(boolean exited, boolean castExists, String cast, String stdout, String report) {
    }

    private Recorded record(Map<String, String> environment, boolean explicitTui, String name) throws Exception {
        Path tape = Files.writeString(workDir.resolve(name + ".tape"), TAPE);
        Path cast = workDir.resolve(name + ".cast");
        Path config = Files.createDirectories(workDir.resolve("config-" + name));
        List<String> options = List.of(
                "-Dtamboui.record=" + cast,
                "-Dtamboui.record.config=" + tape,
                "-Dtamboui.record.width=120",
                "-Dtamboui.record.height=40",
                "-Dtamboui.record.duration=" + RECORD_DURATION_MILLIS,
                "-Dquarkus.http.port=0",
                "-Dquarkus.log.file.enabled=false",
                "-Dvintelli.config.dir=" + config);
        List<String> command = new ArrayList<>(runner(options));
        if (explicitTui) {
            command.add("--tui");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().keySet().removeAll(TERMINAL_ENVIRONMENT);
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        boolean exited = process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        boolean castExists = Files.exists(cast);
        String castText = castExists ? Files.readString(cast, StandardCharsets.UTF_8) : "";
        String out = stdout.get();
        return new Recorded(exited, castExists, castText, out, describe(exited, process, out, stderr.get(), castText));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String describe(boolean exited, Process process, String stdout, String stderr, String cast) {
        String tail = cast.length() > 4_000 ? cast.substring(cast.length() - 4_000) : cast;
        return "exit " + (exited ? process.exitValue() : "(killed)") + "\n--- stdout ---\n" + stdout
                + "\n--- stderr ---\n" + stderr + "\n--- cast tail ---\n" + tail;
    }

    static List<String> runner(List<String> options) {
        String nativeRunner = System.getProperty("native.image.path");
        List<String> command = new ArrayList<>();
        if (nativeRunner != null && Files.isExecutable(Path.of(nativeRunner))) {
            command.add(nativeRunner);
            command.addAll(options);
            return command;
        }
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("-jar");
        command.add(Path.of("target", "quarkus-app", "quarkus-run.jar").toAbsolutePath().toString());
        return command;
    }

    private static CompletableFuture<String> read(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read the TUI output", e);
            }
        });
    }
}
