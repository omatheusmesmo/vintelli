package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starts the packaged runner (the Mandrel image under {@code -Dnative}, the
 * runnable jar otherwise) with {@code --tui} and drives it headlessly through
 * TamboUI's Recording and Playback: a VHS tape waits for the menu bar and then
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
    private static final String MENU_BAR_MARKER = "Navigate";
    private static final String TAPE = """
            Wait@60s /Navigate/
            Sleep 500ms
            Ctrl+C
            """;
    private static final List<String> BACKEND_FAILURES = List.of(
            "BackendProvider", "none could be initialized", "cannot start the TUI");

    @TempDir
    Path workDir;

    @Test
    void theTuiStartsAndDrawsTheMenuBarThroughTapePlayback() throws Exception {
        Path tape = Files.writeString(workDir.resolve("startup.tape"), TAPE);
        Path cast = workDir.resolve("startup.cast");
        Path config = Files.createDirectories(workDir.resolve("config"));
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
        command.add("--tui");

        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        boolean exited = process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String castText = Files.exists(cast) ? Files.readString(cast, StandardCharsets.UTF_8) : "";
        String report = describe(exited, process, stdout.get(), stderr.get(), castText);

        for (String failure : BACKEND_FAILURES) {
            assertFalse(report.contains(failure), "The TUI reported a backend failure\n" + report);
        }
        assertTrue(exited, "The TUI did not exit within " + EXIT_TIMEOUT_SECONDS + "s\n" + report);
        assertTrue(Files.exists(cast), "No cast was recorded\n" + report);
        assertTrue(castText.contains(MENU_BAR_MARKER), "The TUI never drew its menu bar\n" + report);
    }

    private static String describe(boolean exited, Process process, String stdout, String stderr, String cast) {
        String tail = cast.length() > 4_000 ? cast.substring(cast.length() - 4_000) : cast;
        return "exit " + (exited ? process.exitValue() : "(killed)") + "\n--- stdout ---\n" + stdout
                + "\n--- stderr ---\n" + stderr + "\n--- cast tail ---\n" + tail;
    }

    private static List<String> runner(List<String> options) {
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
