package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Turns the inspect render endpoint on for the tests and integration tests
 * that read {@code /tui/render}; the returned config also reaches the native
 * runner, mirroring {@link IdeaMcpTwinResource}.
 */
public final class InspectEnabledResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.of("vintelli.inspect.enabled", "true");
    }

    @Override
    public void stop() {
    }
}
