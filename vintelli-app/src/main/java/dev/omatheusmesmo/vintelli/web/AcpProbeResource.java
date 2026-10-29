package dev.omatheusmesmo.vintelli.web;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Headless anchor for the ACP SDK probe, so the integration tests can drive a
 * full client session (initialize, session/new, session/prompt with streamed
 * session/update, cancel, close) over HTTP on the native runner. The agent
 * under test is the tiny stdio echo agent the test resources configure through
 * {@code vintelli.acp.probe.agent-command} and {@code vintelli.acp.probe.agent-args}.
 *
 * <p>The endpoint is off unless {@code vintelli.acp.probe.enabled} is
 * {@code true} and answers 404 otherwise. Every answer carries the measured
 * phases as strings so failures stay visible on the wire.
 */
@Path("/acp/probe")
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public final class AcpProbeResource {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(30);
    private static final long STREAM_WAIT_MILLIS = 10_000;
    private static final long STREAM_POLL_MILLIS = 50;
    private static final long CANCEL_WAIT_SECONDS = 20;

    private final AcpJsonMapper acpJsonMapper;
    private final boolean enabled;
    private final String command;
    private final List<String> args;

    @Inject
    AcpProbeResource(AcpJsonMapper acpJsonMapper, AcpProbeConfig config) {
        this.acpJsonMapper = acpJsonMapper;
        this.enabled = config.enabled().orElse(false);
        this.command = config.agentCommand().orElse("");
        this.args = config.agentArgs().map(value -> List.of(value.split(","))).orElse(List.of());
    }

    @POST
    @Path("/session")
    @Blocking
    public RestResponse<AcpProbeReply> session() {
        if (!enabled || command.isBlank()) {
            return disabled();
        }
        return RestResponse.ok(runFullSession());
    }

    @POST
    @Path("/mcp")
    @Blocking
    public RestResponse<AcpProbeReply> mcpGate() {
        if (!enabled || command.isBlank()) {
            return disabled();
        }
        return RestResponse.ok(runMcpGate());
    }

    private AcpProbeReply runFullSession() {
        List<String> phases = new ArrayList<>();
        Set<String> streamedSessions = ConcurrentHashMap.newKeySet();
        List<String> chunkTexts = Collections.synchronizedList(new ArrayList<>());
        AcpSyncClient client = client(streamedSessions, chunkTexts);
        String state;
        String detail = "";
        try {
            AcpSchema.InitializeResponse initialize = client.initialize();
            phases.add("initialize:protocolVersion=" + initialize.protocolVersion());

            AcpSchema.NewSessionResponse session = client.newSession(new AcpSchema.NewSessionRequest("/"));
            phases.add("session/new:" + (session.sessionId().isBlank() ? "EMPTY" : "OPENED"));

            AcpSchema.PromptResponse stopped = client.prompt(AcpSchema.PromptRequest.text(session.sessionId(), "echo:hello"));
            boolean streamed = chunkTexts.stream().anyMatch(text -> text.endsWith("echo:hello"));
            phases.add("session/prompt:" + stopped.stopReason() + ":" + (streamed ? "STREAMED" : "NOT_STREAMED"));

            AcpSchema.NewSessionResponse cancellable = client.newSession(new AcpSchema.NewSessionRequest("/"));
            phases.add("session/cancel:" + cancelLongPrompt(client, cancellable.sessionId(), streamedSessions));

            client.closeSession(new AcpSchema.CloseSessionRequest(session.sessionId()));
            phases.add("session/close:CLOSED");
            state = "OK";
        } catch (Exception e) {
            state = "ERROR";
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            client.close();
            phases.add("client/close:CLOSED");
        }
        return new AcpProbeReply(state, List.copyOf(phases), detail);
    }

    private AcpProbeReply runMcpGate() {
        List<String> phases = new ArrayList<>();
        AcpSyncClient client = client(ConcurrentHashMap.newKeySet(), Collections.synchronizedList(new ArrayList<>()));
        String state;
        String detail = "";
        try {
            client.initialize();
            AcpSchema.McpServerStdio server = new AcpSchema.McpServerStdio("echo-mcp", "echo", List.of("gate"), List.of());
            client.newSession(new AcpSchema.NewSessionRequest("/", List.of(server)));
            phases.add("mcp-session:ACCEPTED");
            state = "OK";
        } catch (AcpError e) {
            phases.add("mcp-session:REFUSED:code=" + e.getCode());
            state = "OK";
        } catch (Exception e) {
            state = "ERROR";
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            client.close();
            phases.add("client/close:CLOSED");
        }
        return new AcpProbeReply(state, List.copyOf(phases), detail);
    }

    private String cancelLongPrompt(AcpSyncClient client, String sessionId, Set<String> streamedSessions) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AcpSchema.PromptResponse> pending = executor.submit(
                    () -> client.prompt(AcpSchema.PromptRequest.text(sessionId, "echo:cancel-me")));
            long deadline = System.currentTimeMillis() + STREAM_WAIT_MILLIS;
            while (!streamedSessions.contains(sessionId) && System.currentTimeMillis() < deadline) {
                Thread.sleep(STREAM_POLL_MILLIS);
            }
            client.cancel(new AcpSchema.CancelNotification(sessionId));
            AcpSchema.PromptResponse reply = pending.get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS);
            return String.valueOf(reply.stopReason());
        } catch (Exception e) {
            return "ERROR:" + e.getClass().getSimpleName();
        } finally {
            executor.shutdownNow();
        }
    }

    private AcpSyncClient client(Set<String> streamedSessions, List<String> chunkTexts) {
        AgentParameters parameters = AgentParameters.builder(command).args(args).build();
        StdioAcpClientTransport transport = new StdioAcpClientTransport(parameters, acpJsonMapper);
        return AcpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .promptTimeout(PROMPT_TIMEOUT)
                .clientInfo(new AcpSchema.Implementation("vintelli", "1.0.0"))
                .sessionUpdateHandler(update -> {
                    streamedSessions.add(update.sessionId());
                    if (update.update() instanceof AcpSchema.AgentMessageChunk chunk
                            && chunk.content() instanceof AcpSchema.TextContent content) {
                        chunkTexts.add(content.text());
                    }
                })
                .build();
    }

    private static RestResponse<AcpProbeReply> disabled() {
        return RestResponse.status(RestResponse.Status.NOT_FOUND, new AcpProbeReply("DISABLED", List.of(), ""));
    }

    /**
     * Measured outcome of one probe run: overall state, the per-phase strings
     * produced while driving the session and the exception that broke it.
     */
    public record AcpProbeReply(String state, List<String> phases, String detail) {
    }
}
