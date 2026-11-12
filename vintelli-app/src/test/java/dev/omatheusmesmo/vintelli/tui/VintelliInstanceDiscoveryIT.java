package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omatheusmesmo.vintelli.launch.InstanceRegistry;
import dev.omatheusmesmo.vintelli.launch.InstanceRegistry.Instance;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two headless instances of the packaged runner, each bound to a free port and
 * owning its own project root. A client resolves the MCP URL of the instance
 * that owns a root while both run, the record of a killed instance is ignored
 * and replaced by the next start for that root, and an instance that exits
 * removes its record.
 */
@QuarkusIntegrationTest
class VintelliInstanceDiscoveryIT {

    private static final long TIMEOUT_SECONDS = 180;
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2025-06-18","capabilities":{},
            "clientInfo":{"name":"vintelli-it","version":"0"}}}""";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void aClientResolvesTheInstanceOfItsProjectWhileTwoRunAndSkipsAKilledOne(@TempDir Path work) throws Exception {
        Path state = Files.createDirectories(work.resolve("state"));
        Path rootA = Files.createDirectories(work.resolve("project-a"));
        Path rootB = Files.createDirectories(work.resolve("project-b"));
        InstanceRegistry registry = registry(state);
        List<Process> started = new ArrayList<>();
        try {
            Process first = start(work, "first", rootA, state);
            started.add(first);
            Process second = start(work, "second", rootB, state);
            started.add(second);

            Instance firstInstance = awaitInstance(registry, rootA, first, "first", work);
            Instance secondInstance = awaitInstance(registry, rootB, second, "second", work);
            assertEquals(first.pid(), firstInstance.pid());
            assertEquals(second.pid(), secondInstance.pid());
            assertNotEquals(firstInstance.port(), secondInstance.port());
            assertInitializes(firstInstance.mcpUrl());
            assertInitializes(secondInstance.mcpUrl());

            first.destroyForcibly();
            assertTrue(first.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "The killed instance did not exit");
            Path staleRecord = recordOf(state, first.pid());
            assertTrue(Files.exists(staleRecord), "A killed instance cannot remove its record");
            assertTrue(registry.findByProjectRoot(rootA).isEmpty(), "A stale record must not resolve");
            assertFalse(Files.exists(staleRecord), "A stale record is deleted when it is read");
            assertEquals(secondInstance, registry.findByProjectRoot(rootB).orElseThrow());
            assertInitializes(secondInstance.mcpUrl());

            Process replacement = start(work, "replacement", rootA, state);
            started.add(replacement);
            Instance replaced = awaitInstance(registry, rootA, replacement, "replacement", work);
            assertEquals(replacement.pid(), replaced.pid());
            assertInitializes(replaced.mcpUrl());

            second.destroy();
            assertTrue(second.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "The instance did not exit on SIGTERM");
            assertFalse(Files.exists(recordOf(state, second.pid())), "An exiting instance removes its record");
        } finally {
            started.forEach(Process::destroyForcibly);
        }
    }

    private static InstanceRegistry registry(Path state) {
        return new InstanceRegistry(instances(state), new ObjectMapper(), InstanceRegistry::isAlive);
    }

    private static Path instances(Path state) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("XDG_STATE_HOME", state.toString());
        return InstanceRegistry.defaultDirectory(System.getProperty("os.name"), System.getProperty("user.home"),
                environment);
    }

    private static Path recordOf(Path state, long pid) {
        return instances(state).resolve(pid + ".json");
    }

    private static Instance awaitInstance(InstanceRegistry registry, Path root, Process process, String name,
            Path work) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Optional<Instance> instance = registry.findByProjectRoot(root);
            if (instance.isPresent() && instance.get().pid() == process.pid()) {
                return instance.get();
            }
            if (!process.isAlive()) {
                throw new IllegalStateException("The instance " + name + " exited before publishing its record\n"
                        + read(work.resolve(name + ".err")));
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("No record of the instance " + name + " within " + TIMEOUT_SECONDS + "s");
    }

    private static void assertInitializes(String mcpUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(mcpUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("protocolVersion"), response.body());
    }

    private static Process start(Path work, String name, Path root, Path state) throws IOException {
        Path config = Files.createDirectories(work.resolve("config-" + name));
        List<String> command = new ArrayList<>(VintelliTuiStartupIT.runner(List.of(
                "-Dquarkus.http.port=0",
                "-Dvintelli.local.project-root=" + root,
                "-Dvintelli.config.dir=" + config)));
        command.add("serve");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("XDG_STATE_HOME", state.toString());
        builder.redirectOutput(work.resolve(name + ".out").toFile());
        builder.redirectError(work.resolve(name + ".err").toFile());
        Process process = builder.start();
        process.getOutputStream().close();
        return process;
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "";
    }
}
