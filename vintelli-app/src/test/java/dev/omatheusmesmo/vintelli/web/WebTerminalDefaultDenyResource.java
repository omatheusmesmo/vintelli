package dev.omatheusmesmo.vintelli.web;

import java.nio.file.Path;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/** Enables an authenticated web terminal without granting command actions. */
public final class WebTerminalDefaultDenyResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.ofEntries(
                Map.entry("vintelli.web.enabled", "true"),
                Map.entry("vintelli.local.project-root", Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString()),
                Map.entry("vintelli.mcp.allowed-origins", WebTerminalResource.TRUSTED_ORIGIN),
                Map.entry("quarkus.http.cors.origins", WebTerminalResource.TRUSTED_ORIGIN),
                Map.entry("vintelli.inspect.enabled", "true"),
                Map.entry("quarkus.security.users.embedded.plain-text", "true"),
                Map.entry("quarkus.security.users.embedded.users." + WebTerminalResource.USER,
                        WebTerminalResource.PASSWORD),
                Map.entry("quarkus.security.users.embedded.roles." + WebTerminalResource.USER, "user"));
    }

    @Override
    public void stop() {
    }
}
