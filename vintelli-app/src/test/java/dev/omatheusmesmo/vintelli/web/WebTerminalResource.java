package dev.omatheusmesmo.vintelli.web;

import java.nio.file.Path;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Turns the browser terminal on for the tests and integration tests that
 * reach {@code /tui/ws}: one basic-auth account, the shared guard origin list
 * with one trusted browser origin and the inspect endpoint, so the tests can
 * observe the shared shell state. The foreign origin is trusted by the CORS
 * filter only, so the rejection of that origin is the guard's own decision
 * rather than the CORS filter's. The config reaches the native runner too,
 * mirroring {@link McpGuardResource}.
 */
public final class WebTerminalResource implements QuarkusTestResourceLifecycleManager {

    public static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    public static final String TRUSTED_ORIGIN = "http://trusted.example:6274";
    public static final String FOREIGN_ORIGIN = "http://browser.example:9999";
    public static final String USER = "terminal";
    public static final String PASSWORD = "surface-demo";

    @Override
    public Map<String, String> start() {
        return Map.ofEntries(
                Map.entry("vintelli.web.enabled", "true"),
                Map.entry("vintelli.web.allowed-actions", "terminal.execute"),
                Map.entry("vintelli.local.project-root", PROJECT_ROOT.toString()),
                Map.entry("vintelli.mcp.allowed-origins", TRUSTED_ORIGIN),
                Map.entry("quarkus.http.cors.origins", TRUSTED_ORIGIN + "," + FOREIGN_ORIGIN),
                Map.entry("vintelli.inspect.enabled", "true"),
                Map.entry("quarkus.security.users.embedded.plain-text", "true"),
                Map.entry("quarkus.security.users.embedded.users." + USER, PASSWORD),
                Map.entry("quarkus.security.users.embedded.roles." + USER, "user"));
    }

    @Override
    public void stop() {
    }
}
