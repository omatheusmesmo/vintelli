package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTTP port of the TUI on the packaged runner (the Mandrel image under
 * {@code -Dnative}, the runnable jar otherwise): a second instance with default
 * settings starts beside the first one, and an explicitly requested port that
 * is already taken fails at once with the port and the fix on stderr.
 */
@QuarkusIntegrationTest
class VintelliTuiPortIT {

    private static final long TIMEOUT_SECONDS = 180;
    private static final String TOOL_WINDOW_MARKER = "Terminal";
    private static final String HOLD_TAPE = """
            Wait@60s /Terminal/
            Sleep 20s
            Ctrl+C
            """;
    private static final List<String> TERMINAL_ENVIRONMENT = List.of("TERM", "TERM_PROGRAM", "SSH_CONNECTION",
            "SSH_CLIENT", "SSH_TTY", "KITTY_WINDOW_ID", "GHOSTTY_RESOURCES_DIR", "WEZTERM_EXECUTABLE");

    @Test
    void aTakenExplicitPortFailsAtOnceWithThePortAndTheFixOnStderr(@TempDir Path work) throws Exception {
        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = taken.getLocalPort();
            Process process = start(work, "taken", List.of("-Dquarkus.http.port=" + port), "--tui");
            Outcome outcome = finish(process, work, "taken");

            assertEquals(1, outcome.exitCode(), outcome.describe());
            assertTrue(outcome.stderr().contains("port " + port + " on 127.0.0.1 is already in use"), outcome.describe());
            assertTrue(outcome.stderr().contains("quarkus.http.port"), outcome.describe());
            assertEquals("", outcome.stdout(), outcome.describe());
        }
    }

    @Test
    void twoTuiInstancesRunSideBySideWithDefaultSettings(@TempDir Path work) throws Exception {
        Process first = start(work, "first", List.of(), "--tui");
        Process second = start(work, "second", List.of(), "--tui");

        Outcome firstOutcome = finish(first, work, "first");
        Outcome secondOutcome = finish(second, work, "second");

        assertEquals(0, firstOutcome.exitCode(), firstOutcome.describe());
        assertEquals(0, secondOutcome.exitCode(), secondOutcome.describe());
        assertTrue(Files.readString(work.resolve("second.cast")).contains(TOOL_WINDOW_MARKER), secondOutcome.describe());
    }

    private static Process start(Path work, String name, List<String> options, String... arguments) throws IOException {
        Path state = Files.createDirectories(work.resolve("state-" + name));
        Path config = Files.createDirectories(work.resolve("config-" + name));
        Path tape = Files.writeString(work.resolve(name + ".tape"), HOLD_TAPE);
        List<String> recording = List.of(
                "-Dtamboui.record=" + work.resolve(name + ".cast"),
                "-Dtamboui.record.config=" + tape,
                "-Dtamboui.record.width=120",
                "-Dtamboui.record.height=40",
                "-Dtamboui.record.duration=" + 120_000,
                "-Dvintelli.config.dir=" + config);
        List<String> command = new ArrayList<>(VintelliTuiStartupIT.runner(recording));
        command.addAll(options);
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        TERMINAL_ENVIRONMENT.forEach(builder.environment()::remove);
        builder.environment().put("XDG_STATE_HOME", state.toString());
        builder.redirectOutput(work.resolve(name + ".out").toFile());
        builder.redirectError(work.resolve(name + ".err").toFile());
        Process process = builder.start();
        process.getOutputStream().close();
        return process;
    }

    private static Outcome finish(Process process, Path work, String name) throws Exception {
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("The TUI " + name + " did not exit within " + TIMEOUT_SECONDS + "s");
        }
        return new Outcome(process.exitValue(), read(work.resolve(name + ".out")), read(work.resolve(name + ".err")));
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private record Outcome(int exitCode, String stdout, String stderr) {

        String describe() {
            return "exit " + exitCode + "\n--- stdout ---\n" + stdout + "\n--- stderr ---\n" + stderr;
        }
    }
}
