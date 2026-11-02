package dev.omatheusmesmo.vintelli.web;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Keeps the browser terminal off while one basic-auth account exists, so a
 * request that carries valid credentials reaches the surface gate rather than
 * failing authentication. Mirrors {@link WebTerminalResource} otherwise.
 */
public final class WebTerminalDisabledResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.ofEntries(
                Map.entry("vintelli.web.enabled", "false"),
                Map.entry("quarkus.security.users.embedded.plain-text", "true"),
                Map.entry("quarkus.security.users.embedded.users." + WebTerminalResource.USER,
                        WebTerminalResource.PASSWORD),
                Map.entry("quarkus.security.users.embedded.roles." + WebTerminalResource.USER, "user"));
    }

    @Override
    public void stop() {
    }
}
