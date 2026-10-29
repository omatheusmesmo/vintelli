package dev.omatheusmesmo.vintelli.acp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.omatheusmesmo.vintelli.web.AcpProbeConfig;
import dev.omatheusmesmo.vintelli.web.AcpProbeRun;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Builds one blocking ACP SDK client against the configured stdio agent
 * process. The spawn command, the timeouts and the client identity are wired
 * in this single bean so every consumer (the HTTP probe endpoint and the CLI
 * prompt command) drives the same agent configuration.
 *
 * <p>The configuration keys come from {@link AcpProbeConfig} because the
 * probe boundary was there first: the resources, the probe endpoint and the
 * CLI all point at one agent command. When the full ACP client core lands,
 * this factory stays the spawn point and only its configuration keys widen.
 */
@Singleton
public final class AcpAgentClientFactory {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(30);

    private final AcpJsonMapper acpJsonMapper;
    private final boolean enabled;
    private final String command;
    private final List<String> args;

    @Inject
    AcpAgentClientFactory(AcpJsonMapper acpJsonMapper, AcpProbeConfig config) {
        this.acpJsonMapper = acpJsonMapper;
        this.enabled = config.enabled().orElse(false);
        this.command = config.agentCommand().orElse("");
        this.args = config.agentArgs().map(value -> List.of(value.split(","))).orElse(List.of());
    }

    /**
     * @return true when a stdio agent command is configured and the surface
     *         it serves is switched on
     */
    public boolean isConfigured() {
        return enabled && !command.isBlank();
    }

    public AcpJsonMapper acpJsonMapper() {
        return acpJsonMapper;
    }

    public String command() {
        return command;
    }

    public List<String> args() {
        return args;
    }

    /**
     * Opens the blocking client session with the streamed update bookkeeping
     * the callers already own.
     */
    public AcpSyncClient client(Set<String> streamedSessions, List<String> chunkTexts) {
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

    /**
     * Opens a blocking client for one probe run, wiring every client handler
     * the probe needs (confined file access, permission policy, terminals,
     * elicitation) on top of the same stdio spawn as {@link #client(Set, List)}.
     */
    public AcpSyncClient client(AcpProbeRun run) {
        AgentParameters parameters = AgentParameters.builder(command).args(args).build();
        StdioAcpClientTransport transport = new StdioAcpClientTransport(parameters, acpJsonMapper);
        return AcpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .promptTimeout(PROMPT_TIMEOUT)
                .clientInfo(new AcpSchema.Implementation("vintelli", "1.0.0"))
                .clientCapabilities(AcpProbeRun.clientCapabilities())
                .requestPermissionHandler(run::permission)
                .readTextFileHandler(request -> new AcpSchema.ReadTextFileResponse(
                        unchecked(() -> run.workspace().read(request.path(), request.line(), request.limit()))))
                .writeTextFileHandler(request -> {
                    unchecked(() -> {
                        run.workspace().write(request.path(), request.content());
                        return null;
                    });
                    return new AcpSchema.WriteTextFileResponse();
                })
                .createTerminalHandler(request -> unchecked(() -> run.terminals().create(request)))
                .terminalOutputHandler(request -> run.terminals().output(request.terminalId()))
                .waitForTerminalExitHandler(request -> unchecked(() -> run.terminals().waitForExit(request.terminalId())))
                .killTerminalHandler(request -> run.terminals().kill(request.terminalId()))
                .releaseTerminalHandler(request -> run.terminals().release(request.terminalId()))
                .createElicitationHandler(run::elicitation)
                .sessionUpdateHandler(run::onSessionUpdate)
                .build();
    }

    /**
     * Builds one minimal client with the given advertised capabilities and no
     * handlers, to exercise the SDK {@code build()} contract. Returns the phase
     * string the probe reports.
     */
    public String buildContractCase(String name, AcpSchema.ClientCapabilities capabilities) {
        AgentParameters parameters = AgentParameters.builder(command).args(args).build();
        StdioAcpClientTransport transport = new StdioAcpClientTransport(parameters, acpJsonMapper);
        try {
            AcpClient.sync(transport).clientCapabilities(capabilities).clientInfo(new AcpSchema.Implementation("vintelli", "1.0.0")).build().close();
            return "build:" + name + ":OK";
        } catch (RuntimeException rejected) {
            transport.close();
            return "build:" + name + ":REJECTED:" + rejected.getClass().getSimpleName();
        }
    }

    private static <T> T unchecked(IoCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T call() throws Exception;
    }
}
