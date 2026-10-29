package dev.omatheusmesmo.vintelli.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import dev.omatheusmesmo.vintelli.lsp.FakeLspServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Wires the application under test to a fake language server: instead of
 * starting the server in the test JVM, the resource hands the regular profile
 * command of the fake over to the configuration properties (which also reach
 * the native runner), so the real launch path of the client runs through the
 * profile. The trace path of the fake is exposed through {@link #trace()}, so
 * a test can assert what actually reached the server on the wire.
 */
public final class LspTwinResource implements QuarkusTestResourceLifecycleManager {

    static final String MARKER = "twin-marker";

    private static Path workspace;
    private static Path trace;

    @Override
    public Map<String, String> start() {
        try {
            workspace = Files.createTempDirectory("vintelli-lsp-twin");
            trace = Files.createTempFile("vintelli-lsp-twin", ".trace");
            Files.writeString(trace, "");
            String command = new StringBuilder()
                    .append("-cp,").append(FakeLspServer.classpath())
                    .append(',').append(FakeLspServer.class.getName())
                    .append(",--trace,").append(trace.toAbsolutePath())
                    .append(",--marker,").append(MARKER)
                    .toString();
            return Map.of(
                    "vintelli.lsp.profiles.twin.command", ProcessHandle.current().info().command().orElse("java"),
                    "vintelli.lsp.profiles.twin.args", command,
                    "vintelli.lsp.profiles.twin.workspace-root", workspace.toAbsolutePath().toString(),
                    "vintelli.lsp.debug-endpoint.enabled", "true");
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("cannot build the fake language server command", failure);
        }
    }

    /**
     * @return the trace the fake language server appended, one method per line
     */
    public static String trace() {
        try {
            return Files.readString(trace);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * @return a file inside the workspace of the configured profile
     */
    public static Path document() {
        return workspace.resolve("Fake.java");
    }

    @Override
    public void stop() {
        try {
            Files.walk(workspace)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
            Files.deleteIfExists(trace);
        } catch (IOException ignored) {
        }
        workspace = null;
        trace = null;
    }
}
