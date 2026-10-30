package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Turns the browser terminal on with a cap of two concurrent sessions and a
 * very short idle timeout, so the cap rejection and the idle close can be
 * exercised quickly.
 */
public final class WebTerminalSessionLimitResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.ofEntries(
                Map.entry("vintelli.web.enabled", "true"),
                Map.entry("vintelli.web.max-sessions", "2"),
                Map.entry("vintelli.web.idle-timeout", "PT1S"),
                Map.entry("vintelli.mcp.allowed-origins", WebTerminalResource.TRUSTED_ORIGIN),
                Map.entry("quarkus.http.cors.origins", WebTerminalResource.TRUSTED_ORIGIN),
                Map.entry("vintelli.inspect.enabled", "true"),
                Map.entry("quarkus.security.users.embedded.plain-text", "true"),
                Map.entry("quarkus.security.users.embedded.users." + WebTerminalResource.USER, WebTerminalResource.PASSWORD),
                Map.entry("quarkus.security.users.embedded.roles." + WebTerminalResource.USER, "user"));
    }

    @Override
    public void stop() {
    }
}
