package dev.omatheusmesmo.vintelli.core.http;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.smallrye.config.SmallRyeConfig;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Vert.x filter that keeps browser driven requests from reaching the local
 * endpoints through a rebound DNS name. A loopback bind and the CORS filter
 * do not stop the attack: a page served from a hostile name that resolves to
 * {@code 127.0.0.1} still sends requests with {@code Host} and {@code Origin}
 * set to the hostile name, and a server side CORS checker only tags the
 * response, it does not refuse to run the tool. This guard checks both
 * headers on every request before routing and answers {@code 403} under its
 * own policy.
 *
 * <p>The filter does not select paths: the router resolves spellings such as
 * {@code //mcp} or {@code /%6dcp} to the same routes, so matching the raw path
 * would leave them open. Every route of this application is local, so every
 * request is checked. Requests with no {@code Origin} header (IDE MCP servers,
 * CLI agents, {@code .mcp.json} based tools) are never rejected for lacking
 * one, as the MCP transport specification requires for those clients.
 *
 * <p>Hosts worth trusting are {@code vintelli.mcp.allowed-hosts}, defaulting
 * to the loopback names plus the configured {@code quarkus.http.host}; the
 * browser origins worth trusting are {@code vintelli.mcp.allowed-origins},
 * defaulting to none, meaning no browser origin is accepted. Both are read
 * per request so the values follow runtime overrides.
 *
 * <p>This guard replaces the origin check of {@code quarkus-mcp-server-http}
 * and the global Quarkus host validation. Quarkus host validation has its own
 * host list ({@code quarkus.http.host-validation.allowed-hosts}) and answers
 * {@code 400}; keeping it on would mean two host lists and two status codes
 * for the same rule. It is turned off in {@code application.properties} so
 * this guard is the single host policy for every route, answering {@code 403}.
 */
@Singleton
public final class McpEndpointGuard implements Handler<RoutingContext> {

    private static final Logger LOG = Logger.getLogger(McpEndpointGuard.class);

    static final String ALLOWED_HOSTS = "vintelli.mcp.allowed-hosts";
    static final String ALLOWED_ORIGINS = "vintelli.mcp.allowed-origins";
    static final String HTTP_HOST = "quarkus.http.host";
    static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "[::1]");

    private static final int FILTER_PRIORITY = 500;

    @Override
    public void handle(RoutingContext routingContext) {
        AllowedHostsOrigins rules = allow(ConfigProvider.getConfig().unwrap(SmallRyeConfig.class));
        if (!rules.hostAllowed(host(routingContext))) {
            reject(routingContext, "host");
            return;
        }
        if (!rules.originAllowed(routingContext.request().getHeader("Origin"))) {
            reject(routingContext, "origin");
            return;
        }
        routingContext.next();
    }

    void register(@Observes Filters filters) {
        filters.register(this, FILTER_PRIORITY);
    }

    static AllowedHostsOrigins allow(SmallRyeConfig config) {
        List<String> allowedHosts = new ArrayList<>(
                config.getOptionalValues(ALLOWED_HOSTS, String.class).orElse(LOOPBACK_HOSTS));
        config.getOptionalValue(HTTP_HOST, String.class)
                .filter(host -> !host.isBlank())
                .ifPresent(allowedHosts::add);
        List<String> allowedOrigins = config.getOptionalValues(ALLOWED_ORIGINS, String.class).orElse(List.of());
        return new AllowedHostsOrigins(allowedHosts, allowedOrigins);
    }

    private static String host(RoutingContext routingContext) {
        if (routingContext.request().authority() == null) {
            return null;
        }
        return routingContext.request().authority().host();
    }

    private static void reject(RoutingContext routingContext, String what) {
        LOG.debugf("Rejected foreign %s on a local endpoint", what);
        routingContext.response().setStatusCode(403);
        routingContext.response().end();
    }
}
