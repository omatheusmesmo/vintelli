package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Test configuration of the Host and Origin guard: one origin is trusted by
 * both the guard and the CORS filter, a second origin is trusted by the CORS
 * filter only, so the tests prove the guard rejects browser origins on its
 * own authority instead of delegating to CORS. Auto initialization is on so
 * a raw HTTP {@code tools/call} reaches the tools without an {@code
 * initialize} round trip, and the inspect endpoint is on so the tests can
 * check whether a tool really executed.
 */
public final class McpGuardResource implements QuarkusTestResourceLifecycleManager {

    public static final String TRUSTED_ORIGIN = "http://trusted.example:6274";
    public static final String CORS_ONLY_ORIGIN = "http://browser.example:9999";

    @Override
    public Map<String, String> start() {
        return Map.ofEntries(
                Map.entry("vintelli.inspect.enabled", "true"),
                Map.entry("quarkus.mcp.server.http.streamable.auto-init", "true"),
                Map.entry("vintelli.mcp.allowed-origins", TRUSTED_ORIGIN),
                Map.entry("quarkus.http.cors.origins", TRUSTED_ORIGIN + "," + CORS_ONLY_ORIGIN));
    }

    @Override
    public void stop() {
    }
}
