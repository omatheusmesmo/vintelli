package dev.omatheusmesmo.vintelli.idea;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidgetContributor;
import io.smallrye.config.ConfigMapping;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Blocking bridge to the IntelliJ IDEA MCP server (Settings | Tools | MCP
 * Server, IDEA 2025.2+). Connects lazily on first use, so Vintelli boots
 * cleanly without IDEA running. Streamable HTTP is served by a dedicated IDEA
 * server with an auto-picked port on {@code /stream}; the user copies the
 * endpoint via "Copy HTTP Stream Config", so there is no default URL. IDEA
 * reads the project selection from {@code IJ_MCP_SERVER_PROJECT_PATH} and the
 * restricted-mode token from {@code IJ_MCP_AUTH_TOKEN} on every request.
 *
 * <p>Every call blocks. Long-running tools ({@code execute_run_configuration},
 * {@code execute_terminal_command}) can outlast a minute: pass an explicit
 * timeout per call and only invoke from a worker thread, never the TUI render
 * thread (issue #3 wires status updates back through the render thread).
 *
 * <p>Tool calls run on a dedicated virtual-thread executor owned by the client,
 * not on the ForkJoin common pool: the MCP SDK transport is built on reactor
 * and the JDK HTTP client, which need common-pool workers for their own
 * bookkeeping, so a call parked in {@code Mono.block()} on a common-pool worker
 * hangs on machines where the pool has a single worker (two vCPUs). A call that
 * exceeds its timeout is cancelled, which interrupts the blocked thread and
 * aborts the in-flight request.
 */
@Singleton
public final class IdeaMcpClient implements AutoCloseable, StatusBarWidgetContributor {

    private static final Logger LOG = Logger.getLogger(IdeaMcpClient.class);

    private static final String STREAMABLE_ENDPOINT = "/stream";
    private static final String PROJECT_PATH_HEADER = "IJ_MCP_SERVER_PROJECT_PATH";
    private static final String AUTH_TOKEN_HEADER = "IJ_MCP_AUTH_TOKEN";
    private static final Duration TRANSPORT_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(60);

    private final String baseUri;
    private final String projectPath;
    private final String authToken;
    private final String clientVersion;
    private final McpJsonMapper jsonMapper;
    private final JsonSchemaValidator schemaValidator;
    private final ExecutorService callExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile McpSyncClient client;
    private volatile ConnectionStatus status = ConnectionStatus.disconnected("not connected yet");

    @Inject
    IdeaMcpClient(IdeaMcpConfig config,
                  @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev") String version,
                  McpJsonMapper jsonMapper,
                  JsonSchemaValidator schemaValidator) {
        this(config.url().orElse(null), config.projectPath().orElse(null),
                config.authToken().orElse(null), version, jsonMapper, schemaValidator);
    }

    IdeaMcpClient(String baseUri, String projectPath, String authToken, String clientVersion,
                  McpJsonMapper jsonMapper, JsonSchemaValidator schemaValidator) {
        this.baseUri = trimBaseUri(baseUri);
        this.projectPath = projectPath;
        this.authToken = authToken;
        this.clientVersion = clientVersion;
        this.jsonMapper = jsonMapper;
        this.schemaValidator = schemaValidator;
    }

    public ConnectionStatus status() {
        return status;
    }

    public String projectPath() {
        return projectPath;
    }

    public McpJsonMapper jsonMapper() {
        return jsonMapper;
    }

    @Override
    public List<StatusBarWidget> statusBarWidgets() {
        ConnectionStatus current = status;
        String stateName = current.state().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String text = "IDEA MCP: " + stateName + " — " + current.detail();
        String compactState = switch (current.state()) {
            case CONNECTED -> "on";
            case DISCONNECTED -> "off";
            case UNREACHABLE, ENDPOINT_NOT_FOUND -> "down";
            case AUTH_REQUIRED -> "auth";
            case CONSENT_PENDING -> "wait";
            case TIMEOUT -> "slow";
            case ERROR -> "error";
        };
        return List.of(new StatusBarWidget("idea-mcp", 5, text, "IDEA:" + compactState));
    }

    /**
     * Drops any existing session and connects fresh. Failures keep the client
     * closed and surface as a classified {@link ConnectionStatus}.
     */
    public synchronized ConnectionStatus reconnect() {
        disconnectQuietly();
        return connect();
    }

    public List<IdeaTool> listIdeaTools() {
        McpSyncClient current = ensureConnectedIfMissing();
        if (current == null) {
            return List.of();
        }
        try {
            List<IdeaTool> tools = current.listTools().tools().stream()
                    .map(tool -> new IdeaTool(tool.name(), tool.description() == null ? "" : tool.description()))
                    .toList();
            status = ConnectionStatus.connected();
            return tools;
        } catch (Exception e) {
            return recordFailure(e, List.of());
        }
    }

    public IdeaToolCall callIdeaTool(String name, Map<String, Object> arguments) {
        return callIdeaTool(name, arguments, DEFAULT_CALL_TIMEOUT);
    }

    /**
     * Blocking tool call with its own timeout so slow IDEA actions (run
     * configurations, terminal commands) do not inherit the transport default
     * unless explicitly requested.
     */
    public IdeaToolCall callIdeaTool(String name, Map<String, Object> arguments, Duration timeout) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        if (callExecutor.isShutdown()) {
            return IdeaToolCall.failed(name, safeArguments, new IllegalStateException("client is closed"));
        }
        McpSyncClient current = ensureConnectedIfMissing();
        if (current == null) {
            return IdeaToolCall.failed(name, safeArguments,
                    new IllegalStateException(status.state() + ": " + status.detail()));
        }
        Future<CallToolResult> pending;
        try {
            pending = callExecutor.submit(() -> current.callTool(new CallToolRequest(name, safeArguments)));
        } catch (RejectedExecutionException closed) {
            return IdeaToolCall.failed(name, safeArguments, closed);
        }
        try {
            CallToolResult result = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            status = ConnectionStatus.connected();
            return new IdeaToolCall(name, rendered(result), Boolean.TRUE.equals(result.isError()));
        } catch (TimeoutException exceeded) {
            pending.cancel(true);
            status = ConnectionStatus.timeout("tool '" + name + "' did not finish within "
                    + timeout.toSeconds() + "s; IDEA may still be executing it");
            return IdeaToolCall.failed(name, safeArguments,
                    new TimeoutException("aborted after " + timeout.toSeconds() + "s"));
        } catch (ExecutionException brokered) {
            return wrappedFailure(name, safeArguments, brokered.getCause());
        } catch (InterruptedException cancelled) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return IdeaToolCall.failed(name, safeArguments, cancelled);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        callExecutor.shutdownNow();
        disconnectQuietly();
    }

    private McpSyncClient ensureConnectedIfMissing() {
        if (client == null) {
            connect();
        }
        return client;
    }

    private synchronized ConnectionStatus connect() {
        if (baseUri == null || baseUri.isBlank()) {
            return ConnectionStatus.disconnected("vintelli.idea.mcp.url is not set: copy the HTTP Stream Config "
                    + "from IDEA Settings | Tools | MCP Server");
        }
        try {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder(baseUri)
                    .endpoint(STREAMABLE_ENDPOINT)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .jsonMapper(jsonMapper)
                    .httpRequestCustomizer(ideaHeaders())
                    .build();
            McpSyncClient fresh = McpClient.sync(transport)
                    .clientInfo(new Implementation("vintelli", clientVersion))
                    .jsonSchemaValidator(schemaValidator)
                    .requestTimeout(TRANSPORT_REQUEST_TIMEOUT)
                    .initializationTimeout(INITIALIZATION_TIMEOUT)
                    .build();
            fresh.initialize();
            client = fresh;
            status = ConnectionStatus.connected();
        } catch (Exception e) {
            LOG.warn("IDEA MCP connect failed", e);
            status = classify(e);
        }
        return status;
    }

    private McpSyncHttpClientRequestCustomizer ideaHeaders() {
        return (builder, method, endpoint, body, context) -> {
            if (projectPath != null) {
                builder.header(PROJECT_PATH_HEADER, projectPath);
            }
            if (authToken != null) {
                builder.header(AUTH_TOKEN_HEADER, authToken);
            }
        };
    }

    private IdeaToolCall wrappedFailure(String name, Map<String, Object> arguments, Throwable failure) {
        disconnectQuietly();
        status = classify(failure);
        Exception reported = failure instanceof Exception known ? known : new IllegalStateException(failure);
        return IdeaToolCall.failed(name, arguments, reported);
    }

    private <T> T recordFailure(Exception e, T emptyResult) {
        disconnectQuietly();
        status = classify(e);
        return emptyResult;
    }

    private ConnectionStatus classify(Throwable failure) {
        Integer authorization = authorizationStatus(failure);
        if (authorization != null) {
            return authorization == 403
                    ? ConnectionStatus.consentPending("IDEA answered 403: approve the MCP consent dialog in the "
                            + "IDE, or enable brave mode (Settings | Tools | MCP Server)")
                    : ConnectionStatus.authRequired("IDEA rejected the token (401): set vintelli.idea.mcp.auth-token");
        }
        Integer statusCode = statusCode(failure);
        if (statusCode != null) {
            return ConnectionStatus.endpointNotFound("404 on " + STREAMABLE_ENDPOINT
                    + ": re-copy the HTTP Stream Config from IDEA (" + truncated(failure) + ")");
        }
        if (chainContains(failure, JacksonException.class)) {
            return ConnectionStatus.error("IDEA answered with a malformed payload: " + truncated(failure));
        }
        if (chainContains(failure, ConnectException.class)) {
            return ConnectionStatus.unreachable("IDEA not reachable at " + baseUri
                    + "; start IDEA or check the copied endpoint");
        }
        if (chainContains(failure, IOException.class)) {
            return ConnectionStatus.unreachable(truncated(failure));
        }
        return ConnectionStatus.error(truncated(failure));
    }

    private Integer authorizationStatus(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof McpHttpClientTransportAuthorizationException authorization
                    && authorization.getResponseInfo() != null) {
                return authorization.getResponseInfo().statusCode();
            }
        }
        return null;
    }

    private Integer statusCode(Throwable failure) {
        for (Throwable current : chain(failure)) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            Matcher matcher = Pattern.compile("Status code:(\\d{3})").matcher(message);
            if (matcher.find()) {
                return Integer.valueOf(matcher.group(1));
            }
        }
        return null;
    }

    private static boolean chainContains(Throwable failure, Class<? extends Throwable> type) {
        return chain(failure).stream().anyMatch(type::isInstance);
    }

    private static List<Throwable> chain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private void disconnectQuietly() {
        McpSyncClient current = client;
        client = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private String rendered(CallToolResult result) {
        List<String> texts = new ArrayList<>();
        for (var content : result.content()) {
            if (content instanceof TextContent text) {
                texts.add(text.text());
            } else {
                LOG.warnf("Dropping unsupported content type %s from IDEA tool response",
                        content.getClass().getSimpleName());
            }
        }
        if (result.structuredContent() != null) {
            try {
                return jsonMapper.writeValueAsString(result.structuredContent());
            } catch (Exception ignored) {
                LOG.warn("Could not serialize structuredContent; falling back to text content");
            }
        }
        return String.join("\n", texts);
    }

    private static String trimBaseUri(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String truncated(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
