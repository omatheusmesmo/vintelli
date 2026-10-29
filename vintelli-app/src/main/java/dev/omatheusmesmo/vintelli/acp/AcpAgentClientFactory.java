package dev.omatheusmesmo.vintelli.acp;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.omatheusmesmo.vintelli.web.AcpProbeConfig;
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
}
