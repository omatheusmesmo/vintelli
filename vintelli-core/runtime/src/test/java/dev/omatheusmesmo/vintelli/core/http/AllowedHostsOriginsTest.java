package dev.omatheusmesmo.vintelli.core.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

/**
 * The guard allow list: loopback names and the configured HTTP host are
 * accepted by default, the port and IPv6 brackets are ignored during the
 * match, localhost suffixes are rejected, and a configured origin is the
 * single accepted browser origin.
 */
class AllowedHostsOriginsTest {

    private static final List<String> EMPTY = List.of();

    @Test
    void permitsLoopbackNamesAndIgnoresThePort() {
        AllowedHostsOrigins allow = new AllowedHostsOrigins(
                List.of("localhost", "127.0.0.1", "[::1]", "myhost.example"), EMPTY);

        assertTrue(allow.hostAllowed("127.0.0.1"));
        assertTrue(allow.hostAllowed("127.0.0.1:8080"));
        assertTrue(allow.hostAllowed("localhost"));
        assertTrue(allow.hostAllowed("LocalHost:8080"));
        assertTrue(allow.hostAllowed("[::1]:8080"));
        assertTrue(allow.hostAllowed("myhost.example"));
        assertTrue(allow.hostAllowed("myhost.example:443"));
    }

    @Test
    void rejectsForeignAndSuffixedHosts() {
        AllowedHostsOrigins allow = new AllowedHostsOrigins(
                List.of("localhost", "127.0.0.1", "[::1]"), EMPTY);

        assertFalse(allow.hostAllowed("evil.example.org"));
        assertFalse(allow.hostAllowed("localhost.evil.example"));
        assertFalse(allow.hostAllowed("127.1.0.0"));
    }

    @Test
    void toleratesIPv6AuthorityForms() {
        AllowedHostsOrigins allow = new AllowedHostsOrigins(
                List.of("localhost", "127.0.0.1", "[::1]"), EMPTY);

        assertTrue(allow.hostAllowed("::1"));
        assertTrue(allow.hostAllowed("[::1]"));
    }

    @Test
    void rejectsEveryOriginByDefaultAndAcceptsTheConfiguredOne() {
        AllowedHostsOrigins defaults = new AllowedHostsOrigins(
                List.of("localhost", "127.0.0.1", "[::1]"), EMPTY);

        assertFalse(defaults.originAllowed("http://localhost:6274"));

        AllowedHostsOrigins configured = new AllowedHostsOrigins(
                List.of("localhost", "127.0.0.1", "[::1]"), List.of("http://trusted.example:6274"));

        assertTrue(configured.originAllowed("http://trusted.example:6274"));
        assertTrue(configured.originAllowed("HTTP://TRUSTED.EXAMPLE:6274/"));
        assertFalse(configured.originAllowed("http://trusted.example:9999"));
        assertFalse(configured.originAllowed("http://evil.example.org"));
    }

    @Test
    void aMissingOriginIsAlwaysAccepted() {
        AllowedHostsOrigins allow = new AllowedHostsOrigins(
                List.of("localhost", "127.0.0.1", "[::1]"), EMPTY);

        assertTrue(allow.originAllowed(null));
    }

    @Test
    void resolutionDefaultsToLoopbackHostsAndAddsTheConfiguredHttpHost() {
        AllowedHostsOrigins rules = McpEndpointGuard.allow(config(Map.of(
                "quarkus.http.host", "127.0.0.1")));

        assertTrue(rules.hostAllowed("localhost:8443"));
        assertTrue(rules.hostAllowed("127.0.0.1"));
        assertTrue(rules.hostAllowed("[::1]:8080"));
        assertFalse(rules.hostAllowed("evil.example.org"));
    }

    @Test
    void resolutionReadsTheConfiguredHostAndOriginLists() {
        AllowedHostsOrigins rules = McpEndpointGuard.allow(config(Map.of(
                "vintelli.mcp.allowed-hosts", "vintelli-lab.example,127.0.0.1",
                "vintelli.mcp.allowed-origins", "http://inspector.example:6274")));

        assertTrue(rules.hostAllowed("vintelli-lab.example:8080"));
        assertFalse(rules.hostAllowed("localhost"));
        assertTrue(rules.originAllowed("http://inspector.example:6274"));
        assertFalse(rules.originAllowed("http://inspector.example:5555"));
    }

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(new HashMap<>(properties), "test"))
                .build();
    }
}
