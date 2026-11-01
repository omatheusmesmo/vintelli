package dev.omatheusmesmo.vintelli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import dev.omatheusmesmo.vintelli.acp.EchoAcpAgentResource;
import dev.omatheusmesmo.vintelli.idea.FakeIdeaMcpServer;
import dev.omatheusmesmo.vintelli.web.IdeaMcpTwinResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The AEsh command layer replayed against the packaged artifact: every case
 * spawns the runner (the Mandrel image under {@code -Dnative}, the runnable
 * jar otherwise) in command mode and asserts the captured stdout, stderr and
 * exit code, so the generated command model, the capability ports and the
 * POSIX exit mapping are proven outside the test JVM. Each assertion carries
 * the full captured output so a failing native run reports what the image
 * actually printed.
 */
@QuarkusIntegrationTest
@WithTestResource(IdeaMcpTwinResource.class)
class VintelliCliCommandsIT {

    private static final long TIMEOUT_SECONDS = 60;

    @Test
    void inspectPrintsTheSelectionAndTheCapabilityRows() throws Exception {
        CliRun run = cli("inspect");
        assertExit(run, 0);
        assertTrue(run.stdout().contains("Shell: tool window '', file ''"), run.describe());
        assertTrue(run.stdout().contains("run: local ("), run.describe());
        assertTrue(run.stdout().contains("terminal: local (local shell process)"), run.describe());
        assertTrue(run.stdout().contains("read-file: "), run.describe());
        assertTrue(run.stdout().contains("diagnostics: unavailable: "), run.describe());
    }

    @Test
    void problemsReportsTheUnavailableDiagnosticsCapability() throws Exception {
        CliRun run = cli("problems", "src/Main.java");
        assertExit(run, 1);
        assertTrue(run.stdout().contains("Problems capability is unavailable: "), run.describe());
        assertTrue(run.stdout().contains("'diagnostics'"), run.describe());
    }

    @Test
    void runWithoutANameListsWhatCanRun() throws Exception {
        CliRun run = cli("run");
        assertExit(run, 0);
        assertTrue(run.stdout().contains("maven:") || run.stdout().contains("No run configurations"), run.describe());
    }

    @Test
    void unknownRunConfigurationFailsWithTheAvailableNames() throws Exception {
        CliRun run = cli("run", "vintelli-unknown");
        assertExit(run, 1);
        assertTrue(run.stdout().contains("Unknown run configuration 'vintelli-unknown'"), run.describe());
    }

    @Test
    void gatedTerminalCommandsAskForExplicitConfirmation() throws Exception {
        CliRun run = cli("terminal", "sudo", "reboot");
        assertExit(run, 1);
        assertTrue(run.stdout().contains("Vintelli asks for explicit confirmation"), run.describe());
        assertTrue(run.stdout().contains("terminal --yes sudo reboot"), run.describe());
    }

    @Test
    void compoundTerminalCommandRequiresConfirmation() throws Exception {
        CliRun run = cli("terminal", "echo", "hello;", "echo", "unexpected");
        assertExit(run, 1);
        assertTrue(run.stdout().contains("Vintelli asks for explicit confirmation"), run.describe());
    }

    @Test
    void terminalRunsTheCommandThroughTheSelectedProvider() throws Exception {
        CliRun run = cli("terminal", "--yes", "java", "--version");
        assertExit(run, 0);
        assertTrue(run.stdout().contains("openjdk"), run.describe());
    }

    @Test
    void ideaTerminalTimeoutFailsInThePackagedRunner() throws Exception {
        CliRun run = ideaTerminal("{\"command_output\":\"partial output\",\"command_exit_code\":124,\"is_timed_out\":true}");
        assertTrue(run.stdout().contains("IDEA terminal command timed out: partial output"), run.describe());
    }

    @Test
    void ideaTerminalResultWithoutExitCodeFailsInThePackagedRunner() throws Exception {
        CliRun run = ideaTerminal("{\"command_output\":\"partial output\"}");
        assertTrue(run.stdout().contains("IDEA terminal result is missing an exit code: partial output"), run.describe());
    }

    @Test
    void malformedIdeaTerminalResultFailsInThePackagedRunner() throws Exception {
        CliRun run = ideaTerminal("not-json");
        assertTrue(run.stdout().contains("Cannot parse terminal result"), run.describe());
    }

    @Test
    void agentPromptCompletesTheSessionRoundTrip() throws Exception {
        CliRun run = cli("agents", "prompt", "echo:hello");
        assertExit(run, 0);
        assertTrue(run.stdout().contains("echo:hello"), run.describe());
        assertTrue(run.stdout().contains("Stop reason: end_turn"), run.describe());
    }

    @Test
    void bareAgentsCommandPointsAtThePromptUsage() throws Exception {
        CliRun run = cli("agents");
        assertExit(run, 2);
        assertTrue(run.stdout().contains("agents prompt"), run.describe());
    }

    @Test
    void unknownCommandFailsWithThePosixCode() throws Exception {
        CliRun run = cli("definitely-not-a-command");
        assertExit(run, 127);
        assertTrue(run.stderr().contains("Unknown command: definitely-not-a-command"), run.describe());
    }

    @Test
    void helpPrintsTheUsageOfACommandAndExitsZero() throws Exception {
        CliRun run = cli("run", "--help");
        assertExit(run, 0);
        assertTrue(run.stdout().contains("Usage: run"), run.describe());
    }

    @Test
    void replExecutesCommandsFromStdinAndStopsOnExit() throws Exception {
        CliRun run = launch("inspect\nexit\n", "--repl");
        assertExit(run, 0);
        assertTrue(run.stdout().contains("Shell: tool window '', file ''"), run.describe());
        assertTrue(run.stdout().contains("terminal: local (local shell process)"), run.describe());
    }

    private static void assertExit(CliRun run, int expected) {
        assertEquals(expected, run.exitCode(), run.describe());
    }

    private static CliRun cli(String... arguments) throws Exception {
        return launch(null, arguments);
    }

    private static CliRun launch(String stdin, String... arguments) throws Exception {
        EchoAcpAgentResource agent = new EchoAcpAgentResource();
        try {
            return run(agent.start(), stdin, arguments);
        } finally {
            agent.stop();
        }
    }

    private static CliRun ideaTerminal(String terminalResult) throws Exception {
        FakeIdeaMcpServer twin = IdeaMcpTwinResource.twin();
        twin.behaveNormally();
        twin.terminalResult(terminalResult);

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        List<String> options = List.of(
                "-Dvintelli.idea.mcp.url=" + twin.baseUrl(),
                "-Dvintelli.idea.mcp.auth-token=vintelli-token",
                "-Dvintelli.idea.debug-endpoint.enabled=true",
                "-Dquarkus.http.port=" + port,
                "-Dquarkus.log.file.enabled=false");
        List<String> command = new ArrayList<>(runner(options));
        command.add("--repl");
        Process process = new ProcessBuilder(command).start();
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        try {
            awaitIdeaConnection(process, port);
            try (OutputStream input = process.getOutputStream()) {
                input.write("terminal --yes echo marker\nexit\n".getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                CliRun partial = new CliRun(List.of("terminal", "--yes", "echo", "marker"), -1,
                        stdout.get(), stderr.get());
                throw new IllegalStateException("The IDEA terminal CLI did not finish within "
                        + TIMEOUT_SECONDS + "s\n" + partial.describe());
            }
            return new CliRun(List.of("terminal", "--yes", "echo", "marker"), process.exitValue(),
                    stdout.get(), stderr.get());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void awaitIdeaConnection(Process process, int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/idea/reconnect"))
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // The Quarkus HTTP listener is not ready yet.
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("The packaged runner did not connect to the IDEA MCP twin on port " + port
                + "; process alive: " + process.isAlive());
    }

    private static CliRun run(Map<String, String> agentProperties, String stdin, String... arguments)
            throws Exception {
        List<String> options = new ArrayList<>();
        for (Map.Entry<String, String> property : agentProperties.entrySet()) {
            options.add("-D" + property.getKey() + "=" + property.getValue());
        }
        options.add("-Dquarkus.http.port=0");
        options.add("-Dquarkus.log.file.enabled=false");
        List<String> command = new ArrayList<>(runner(options));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).start();
        try (OutputStream input = process.getOutputStream()) {
            if (stdin != null) {
                input.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            CliRun partial = new CliRun(List.of(arguments), -1, stdout.get(), stderr.get());
            throw new IllegalStateException("The CLI did not finish within " + TIMEOUT_SECONDS + "s\n"
                    + partial.describe());
        }
        return new CliRun(List.of(arguments), process.exitValue(), stdout.get(), stderr.get());
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
                throw new IllegalStateException("Cannot read the CLI output", e);
            }
        });
    }

    private record CliRun(List<String> arguments, int exitCode, String stdout, String stderr) {

        String describe() {
            return "exit " + exitCode + " for " + arguments + "\n--- stdout ---\n" + stdout
                    + "\n--- stderr ---\n" + stderr;
        }
    }
}
