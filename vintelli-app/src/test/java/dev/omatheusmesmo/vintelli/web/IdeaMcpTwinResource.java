package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.idea.FakeIdeaMcpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Starts the {@link FakeIdeaMcpServer} twin in the test JVM on an ephemeral
 * port and wires the application under test to it through the returned config
 * properties, which also reach the native runner. The twin is exposed through
 * {@link #twin()} so a test can switch its behavior between requests.
 */
public final class IdeaMcpTwinResource implements QuarkusTestResourceLifecycleManager {

    private static volatile FakeIdeaMcpServer twin;

    public static FakeIdeaMcpServer twin() {
        return twin;
    }

    @Override
    public Map<String, String> start() {
        try {
            twin = new FakeIdeaMcpServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of(
                "vintelli.idea.mcp.url", twin.baseUrl(),
                "vintelli.idea.mcp.auth-token", "vintelli-token",
                "vintelli.idea.debug-endpoint.enabled", "true");
    }

    @Override
    public void stop() {
        if (twin != null) {
            twin.close();
            twin = null;
        }
    }
}
