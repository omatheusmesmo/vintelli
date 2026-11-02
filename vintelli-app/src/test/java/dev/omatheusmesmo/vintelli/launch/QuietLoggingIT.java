package dev.omatheusmesmo.vintelli.launch;

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

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Logging per mode against the packaged artifact (the Mandrel image under
 * {@code -Dnative}, the runnable jar otherwise): the banner and the log lines
 * never reach stdout, {@code --debug} writes the log file and prints its
 * path on stderr, and the TUI failure points at the log file.
 */
@QuarkusIntegrationTest
class QuietLoggingIT {

    private static final long TIMEOUT_SECONDS = 60;
    private static final String BANNER_MARK = "Powered by Quarkus";

    @Test
    void cliStdoutCarriesOnlyTheCommandOutput(@TempDir Path state) throws Exception {
        Run run = launch(state, "inspect");

        assertEquals(0, run.exitCode(), run.describe());
        assertTrue(run.stdout().startsWith("Shell: tool window"), run.describe());
        assertNoLogText(run.stdout(), run);
        assertFalse(Files.exists(logFile(state)), "a plain CLI command must not create a log file\n" + run.describe());
    }

    @Test
    void debugWritesTheLogFileAndPrintsItsPathOnStderr(@TempDir Path state) throws Exception {
        Run run = launch(state, "--debug", "inspect");

        assertEquals(0, run.exitCode(), run.describe());
        assertTrue(run.stderr().contains("Vintelli debug log: " + logFile(state)), run.describe());
        assertTrue(Files.isRegularFile(logFile(state)), run.describe());
        assertTrue(Files.size(logFile(state)) > 0, "the debug log file is empty\n" + run.describe());
        assertTrue(run.stdout().startsWith("Shell: tool window"), run.describe());
        assertNoLogText(run.stdout(), run);
    }

    @Test
    void environmentVariableTurnsDebugOn(@TempDir Path state) throws Exception {
        Run run = launch(state, Map.of("VINTELLI_DEBUG", "1"), "inspect");

        assertEquals(0, run.exitCode(), run.describe());
        assertTrue(run.stderr().contains("Vintelli debug log: " + logFile(state)), run.describe());
        assertTrue(Files.isRegularFile(logFile(state)), run.describe());
    }

    @Test
    void tuiFailureKeepsStdoutFreeOfLogsAndPointsAtTheLogFile(@TempDir Path state) throws Exception {
        Run run = launch(state, Map.of(), List.of("-Dvintelli.tui.theme=bogus",
                "-Dvintelli.config.dir=" + state.resolve("config")), "--tui");

        assertEquals(2, run.exitCode(), run.describe());
        assertTrue(run.stderr().contains("Vintelli cannot start the TUI:"), run.describe());
        assertTrue(run.stderr().contains("Details are in the log file: " + logFile(state)), run.describe());
        assertNoLogText(run.stdout(), run);
        assertTrue(Files.isRegularFile(logFile(state)), run.describe());
    }

    private static void assertNoLogText(String stdout, Run run) {
        assertFalse(stdout.contains(BANNER_MARK), run.describe());
        assertFalse(stdout.contains("__  ____"), run.describe());
        assertFalse(stdout.matches("(?s).*\\b(INFO|WARN|DEBUG|ERROR)\\b.*\\[.*"), run.describe());
    }

    private static Path logFile(Path state) {
        return state.resolve("vintelli").resolve("vintelli.log");
    }

    private static Run launch(Path state, String... arguments) throws Exception {
        return launch(state, Map.of(), List.of(), arguments);
    }

    private static Run launch(Path state, Map<String, String> env, String... arguments) throws Exception {
        return launch(state, env, List.of(), arguments);
    }

    private static Run launch(Path state, Map<String, String> env, List<String> options, String... arguments)
            throws Exception {
        List<String> allOptions = new ArrayList<>(options);
        allOptions.add("-Dquarkus.http.port=0");
        List<String> command = new ArrayList<>(runner(allOptions));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("XDG_STATE_HOME", state.toString());
        builder.environment().remove("VINTELLI_DEBUG");
        builder.environment().putAll(env);
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            Run partial = new Run(List.of(arguments), -1, stdout.get(), stderr.get());
            throw new IllegalStateException("The runner did not finish within " + TIMEOUT_SECONDS + "s\n"
                    + partial.describe());
        }
        return new Run(List.of(arguments), process.exitValue(), stdout.get(), stderr.get());
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
                throw new IllegalStateException("Cannot read the process output", e);
            }
        });
    }

    private record Run(List<String> arguments, int exitCode, String stdout, String stderr) {

        String describe() {
            return "exit " + exitCode + " for " + arguments + "\n--- stdout ---\n" + stdout
                    + "\n--- stderr ---\n" + stderr;
        }
    }
}
