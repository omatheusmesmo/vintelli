package dev.omatheusmesmo.vintelli.idea;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal debug surface for the IDEA MCP connection, mainly there so the
 * integration tests can drive {@link IdeaMcpClient} through plain HTTP
 * instead of the TUI. Every request opens its own throwaway MCP session,
 * which is intentionally wasteful: this is a diagnostic endpoint, not part
 * of the TUI flow.
 */
@Singleton
@Path("/idea")
public class IdeaClientResource {

    private final IdeaMcpConfig config;
    private final String clientVersion;

    @Inject
    IdeaClientResource(IdeaMcpConfig config,
                       @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev") String version) {
        this.config = config;
        this.clientVersion = version;
    }

    @GET
    @Path("/tools")
    public String tools() {
        try (IdeaMcpClient client = newClient()) {
            return client.listIdeaTools().stream()
                    .map(IdeaTool::name)
                    .collect(Collectors.joining("\n"));
        }
    }

    @GET
    @Path("/tool")
    public String callTool(@QueryParam("name") String name) {
        try (IdeaMcpClient client = newClient()) {
            return client.callIdeaTool(name, Map.of()).resultText();
        }
    }

    private IdeaMcpClient newClient() {
        return new IdeaMcpClient(config.url().orElse(null), config.projectPath().orElse(null),
                config.authToken().orElse(null), clientVersion);
    }
}
