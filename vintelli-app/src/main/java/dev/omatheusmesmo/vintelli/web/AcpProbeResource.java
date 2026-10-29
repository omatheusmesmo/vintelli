package dev.omatheusmesmo.vintelli.web;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.omatheusmesmo.vintelli.acp.AcpAgentClientFactory;
import dev.omatheusmesmo.vintelli.core.acp.AcpAgentGate;
import dev.omatheusmesmo.vintelli.core.acp.AcpGateException;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Headless anchor for the ACP SDK probe, so the integration tests can drive the
 * client over HTTP on the native runner. The agent under test is the stdio
 * twin, or a foreign agent, that the test resources configure through
 * {@code vintelli.acp.probe.agent-command} and {@code vintelli.acp.probe.agent-args},
 * and the client itself is built by {@link AcpAgentClientFactory}, shared with
 * the CLI prompt command.
 *
 * <p>The endpoint is off unless {@code vintelli.acp.probe.enabled} is
 * {@code true} and answers 404 otherwise. Every answer carries the measured
 * phases as strings so failures stay visible on the wire.
 */
@Path("/acp/probe")
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public final class AcpProbeResource {

    private static final long CANCEL_WAIT_SECONDS = 20;
    private static final long STREAM_WAIT_MILLIS = 10_000;
    private static final long STREAM_POLL_MILLIS = 50;

    private final AcpAgentClientFactory agents;

    @Inject
    AcpProbeResource(AcpAgentClientFactory agents) {
        this.agents = agents;
    }

    @POST
    @Path("/session")
    @Blocking
    public RestResponse<AcpProbeReply> session() {
        return answer(this::runFullSession);
    }

    @POST
    @Path("/handshake")
    @Blocking
    public RestResponse<AcpProbeReply> handshake() {
        return answer(this::runHandshake);
    }

    @POST
    @Path("/mcp")
    @Blocking
    public RestResponse<AcpProbeReply> mcpGate() {
        return answer(this::runMcpGate);
    }

    @POST
    @Path("/contract")
    @Blocking
    public RestResponse<AcpProbeReply> buildContract() {
        return answer(this::runBuildContract);
    }

    private RestResponse<AcpProbeReply> answer(ProbeScenario scenario) {
        if (!agents.isConfigured()) {
            return RestResponse.status(RestResponse.Status.NOT_FOUND, new AcpProbeReply("DISABLED", List.of(), ""));
        }
        AcpProbeRun run;
        try {
            run = AcpProbeRun.create();
        } catch (IOException e) {
            return RestResponse.ok(new AcpProbeReply("ERROR", List.of(), e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return RestResponse.ok(scenario.run(run));
    }

    private AcpProbeReply runFullSession(AcpProbeRun run) {
        AcpSyncClient client = agents.client(run);
        String state;
        String detail = "";
        try {
            initialize(client, run);
            AcpSchema.NewSessionResponse session = client.newSession(new AcpSchema.NewSessionRequest(run.root().toString()));
            run.phases().add("session/new:" + (session.sessionId().isBlank() ? "EMPTY" : "OPENED"));

            AcpSchema.PromptResponse echoed = client.prompt(AcpSchema.PromptRequest.text(session.sessionId(), "echo:hello"));
            boolean streamed = run.chunks().stream().anyMatch(text -> text.endsWith("echo:hello"));
            run.phases().add("session/prompt:" + echoed.stopReason() + ":" + (streamed ? "STREAMED" : "NOT_STREAMED"));

            run.resetUpdateKinds();
            client.prompt(AcpSchema.PromptRequest.text(session.sessionId(), "kinds"));
            run.phases().add("updates:" + run.updateKindsObserved());

            client.prompt(AcpSchema.PromptRequest.text(session.sessionId(), "permit"));
            client.prompt(AcpSchema.PromptRequest.text(session.sessionId(), "fs:" + run.root()));
            run.phases().add("fs/write-on-disk:" + run.readBack("out.txt"));
            client.prompt(AcpSchema.PromptRequest.text(session.sessionId(), "terminal"));
            client.prompt(AcpSchema.PromptRequest.text(session.sessionId(), "elicit"));

            AcpSchema.NewSessionResponse cancellable = client.newSession(new AcpSchema.NewSessionRequest(run.root().toString()));
            run.phases().add("session/cancel:" + cancelLongPrompt(client, cancellable.sessionId(), run));

            client.closeSession(new AcpSchema.CloseSessionRequest(session.sessionId()));
            run.phases().add("session/close:CLOSED");
            run.phases().addAll(run.chunks().stream().map(text -> "agent:" + text).toList());
            state = "OK";
        } catch (Exception e) {
            state = "ERROR";
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            client.close();
            run.close();
            run.phases().add("client/close:CLOSED");
            run.phases().add("terminals:released:" + run.releasedTerminalCount());
        }
        return new AcpProbeReply(state, List.copyOf(run.phases()), detail);
    }

    private AcpProbeReply runHandshake(AcpProbeRun run) {
        AcpSyncClient client = agents.client(run);
        String state;
        String detail = "";
        try {
            try {
                AcpSchema.NewSessionResponse session = openSession(client, run);
                client.closeSession(new AcpSchema.CloseSessionRequest(session.sessionId()));
                run.phases().add("session/close:CLOSED");
            } catch (AcpGateException refusal) {
                run.phases().add("initialize:REJECTED:" + refusal.getMessage());
            }
            state = "OK";
        } catch (Exception e) {
            state = "ERROR";
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            client.close();
            run.close();
            run.phases().add("client/close:CLOSED");
            run.phases().add("terminals:released:" + run.releasedTerminalCount());
        }
        return new AcpProbeReply(state, List.copyOf(run.phases()), detail);
    }

    private AcpProbeReply runMcpGate(AcpProbeRun run) {
        AcpSyncClient client = agents.client(run);
        String state;
        String detail = "";
        try {
            initialize(client, run);
            for (AcpSchema.McpServer server : mcpServers()) {
                String transport = transportLabel(server);
                try {
                    AcpAgentGate.requireMcpServerAdvertised(server, client.getAgentCapabilities());
                    client.newSession(new AcpSchema.NewSessionRequest(run.root().toString(), List.of(server)));
                    run.phases().add("mcp:" + transport + ":ACCEPTED");
                } catch (AcpGateException gated) {
                    run.phases().add("mcp:" + transport + ":GATED");
                } catch (AcpError refused) {
                    run.phases().add("mcp:" + transport + ":REFUSED:code=" + refused.getCode());
                }
            }
            state = "OK";
        } catch (Exception e) {
            state = "ERROR";
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            client.close();
            run.close();
            run.phases().add("client/close:CLOSED");
            run.phases().add("terminals:released:" + run.releasedTerminalCount());
        }
        return new AcpProbeReply(state, List.copyOf(run.phases()), detail);
    }

    private AcpProbeReply runBuildContract(AcpProbeRun run) {
        List<String> phases = run.phases();
        phases.add(agents.buildContractCase("read-without-handler", AcpSchema.ClientCapabilities.builder().readTextFile().build()));
        phases.add(agents.buildContractCase("write-without-handler", AcpSchema.ClientCapabilities.builder().writeTextFile().build()));
        phases.add(agents.buildContractCase("terminal-without-handler", AcpSchema.ClientCapabilities.builder().terminal().build()));
        phases.add(agents.buildContractCase("elicitation-without-handler",
                AcpSchema.ClientCapabilities.builder().elicitation(AcpSchema.ElicitationCapabilities.formAndUrl()).build()));
        phases.add(agents.buildContractCase("no-capabilities", AcpSchema.ClientCapabilities.builder().build()));
        return new AcpProbeReply("OK", List.copyOf(phases), "");
    }

    private AcpSchema.InitializeResponse initialize(AcpSyncClient client, AcpProbeRun run) {
        AcpSchema.InitializeResponse initialize = client.initialize();
        AcpAgentGate.requireSupportedVersion(initialize);
        run.phases().add("initialize:protocolVersion=" + initialize.protocolVersion());
        return initialize;
    }

    private AcpSchema.NewSessionResponse openSession(AcpSyncClient client, AcpProbeRun run) {
        initialize(client, run);
        AcpSchema.NewSessionResponse session = client.newSession(new AcpSchema.NewSessionRequest(run.root().toString()));
        run.phases().add("session/new:" + (session.sessionId().isBlank() ? "EMPTY" : "OPENED"));
        return session;
    }

    private String cancelLongPrompt(AcpSyncClient client, String sessionId, AcpProbeRun run) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AcpSchema.PromptResponse> pending = executor.submit(
                    () -> client.prompt(AcpSchema.PromptRequest.text(sessionId, "echo:cancel-me")));
            long deadline = System.currentTimeMillis() + STREAM_WAIT_MILLIS;
            Set<String> streamed = run.streamedSessions();
            while (!streamed.contains(sessionId) && System.currentTimeMillis() < deadline) {
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

    private static List<AcpSchema.McpServer> mcpServers() {
        return List.of(
                new AcpSchema.McpServerStdio("echo-mcp", "echo", List.of("gate"), List.of()),
                new AcpSchema.McpServerHttp("docs-http", "https://example.invalid/mcp", List.of()),
                new AcpSchema.McpServerSse("docs-sse", "https://example.invalid/sse", List.of()));
    }

    private static String transportLabel(AcpSchema.McpServer server) {
        return switch (server) {
            case AcpSchema.McpServerStdio _ -> "stdio";
            case AcpSchema.McpServerHttp _ -> "http";
            case AcpSchema.McpServerSse _ -> "sse";
            default -> "unknown";
        };
    }

    @FunctionalInterface
    private interface ProbeScenario {
        AcpProbeReply run(AcpProbeRun run);
    }

    /**
     * Measured outcome of one probe run: overall state, the per-phase strings
     * produced while driving the session and the exception that broke it.
     */
    public record AcpProbeReply(String state, List<String> phases, String detail) {
    }
}
