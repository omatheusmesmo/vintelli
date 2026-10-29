package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.idea.ConnectionState;
import dev.omatheusmesmo.vintelli.idea.ConnectionStatus;
import dev.omatheusmesmo.vintelli.idea.IdeaMcpClient;
import dev.omatheusmesmo.vintelli.idea.IdeaTool;
import dev.omatheusmesmo.vintelli.idea.IdeaToolCall;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Headless anchor for the IDEA MCP connection, so the integration tests can
 * drive {@link IdeaMcpClient} over HTTP on the native runner instead of the
 * TUI. Every answer carries the classified {@link ConnectionState} in the body
 * and maps it to an HTTP status.
 *
 * <p>The endpoint is off unless {@code vintelli.idea.debug-endpoint.enabled}
 * is {@code true} and answers 404 otherwise, because it talks to the user's IDE
 * with the configured token. Even when enabled it only calls read-only tools
 * whose name matches {@code get_[a-z_]+}; anything else is rejected with 400.
 */
@Path("/idea")
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public final class IdeaClientResource {

    private static final Pattern READ_ONLY_TOOL = Pattern.compile("get_[a-z_]+");
    private static final long MIN_TIMEOUT_MILLIS = 100;
    private static final long MAX_TIMEOUT_MILLIS = 60_000;

    private final IdeaMcpClient client;
    private final boolean enabled;

    @Inject
    IdeaClientResource(IdeaMcpClient client,
                       @ConfigProperty(name = "vintelli.idea.debug-endpoint.enabled", defaultValue = "false") boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    @POST
    @Path("/reconnect")
    @Blocking
    public RestResponse<IdeaClientReply> reconnect() {
        if (!enabled) {
            return disabled();
        }
        return classified(client.reconnect(), "");
    }

    @GET
    @Path("/tools")
    @Blocking
    public RestResponse<IdeaClientReply> tools() {
        if (!enabled) {
            return disabled();
        }
        String names = client.listIdeaTools().stream()
                .map(IdeaTool::name)
                .collect(Collectors.joining("\n"));
        return classified(client.status(), names);
    }

    @GET
    @Path("/tool")
    @Blocking
    public RestResponse<IdeaClientReply> callTool(@QueryParam("name") String name,
                                                  @QueryParam("timeoutMillis") @DefaultValue("10000") long timeoutMillis) {
        if (!enabled) {
            return disabled();
        }
        if (name == null || !READ_ONLY_TOOL.matcher(name).matches()) {
            return rejected("only read-only tools matching " + READ_ONLY_TOOL.pattern() + " can be called");
        }
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            return rejected("timeoutMillis must be between " + MIN_TIMEOUT_MILLIS + " and " + MAX_TIMEOUT_MILLIS);
        }
        IdeaToolCall call = client.callIdeaTool(name, Map.of(), Duration.ofMillis(timeoutMillis));
        ConnectionStatus status = client.status();
        if (call.isError() && status.state() == ConnectionState.CONNECTED) {
            return RestResponse.ResponseBuilder
                    .create(ToolErrorStatus.INSTANCE, new IdeaClientReply("TOOL_ERROR", status.detail(), call.resultText()))
                    .build();
        }
        return classified(status, call.resultText());
    }

    private static RestResponse<IdeaClientReply> classified(ConnectionStatus status, String text) {
        return RestResponse.status(httpStatus(status.state()),
                new IdeaClientReply(status.state().name(), status.detail(), text));
    }

    private static RestResponse<IdeaClientReply> disabled() {
        return RestResponse.status(RestResponse.Status.NOT_FOUND, new IdeaClientReply("DISABLED", "", ""));
    }

    private static RestResponse<IdeaClientReply> rejected(String detail) {
        return RestResponse.status(RestResponse.Status.BAD_REQUEST, new IdeaClientReply("REJECTED", detail, ""));
    }

    private static RestResponse.Status httpStatus(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> RestResponse.Status.OK;
            case AUTH_REQUIRED -> RestResponse.Status.UNAUTHORIZED;
            case CONSENT_PENDING -> RestResponse.Status.FORBIDDEN;
            case ENDPOINT_NOT_FOUND -> RestResponse.Status.NOT_FOUND;
            case UNREACHABLE, DISCONNECTED -> RestResponse.Status.SERVICE_UNAVAILABLE;
            case TIMEOUT -> RestResponse.Status.GATEWAY_TIMEOUT;
            case ERROR -> RestResponse.Status.BAD_GATEWAY;
        };
    }

    /**
     * HTTP 422: IDEA answered and the session is healthy, but the tool result
     * is an {@code isError} payload, which must not look like a broken connection.
     */
    private enum ToolErrorStatus implements Response.StatusType {
        INSTANCE;

        @Override
        public int getStatusCode() {
            return 422;
        }

        @Override
        public Response.Status.Family getFamily() {
            return Response.Status.Family.CLIENT_ERROR;
        }

        @Override
        public String getReasonPhrase() {
            return "Unprocessable Content";
        }
    }

    /**
     * Payload of every answer: the classified state, its human readable detail
     * and the tool names or tool output the request produced.
     */
    public record IdeaClientReply(String state, String detail, String text) {
    }
}
