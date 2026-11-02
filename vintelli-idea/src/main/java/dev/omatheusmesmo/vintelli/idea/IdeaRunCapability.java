package dev.omatheusmesmo.vintelli.idea;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import io.modelcontextprotocol.json.McpJsonMapper;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * IDEA backed implementation of {@link RunCapability}: lists the run
 * configurations exposed by the IntelliJ MCP server and executes one of them
 * through {@code execute_run_configuration}. IDEA returns one output snapshot
 * after the run finishes, so the output consumer receives the captured lines
 * in one batch, followed by typed completion. The handle is best effort (IDEA
 * exposes no cancel tool).
 */
@Unremovable
@Singleton
public final class IdeaRunCapability implements RunCapability {

    private record IdeaRunConfig(String name, String description,
            boolean supportsDynamicLaunchOverrides) {
    }

    private record IdeaRunConfigResponse(List<IdeaRunConfig> configurations) {
    }

    private record IdeaRunResult(String output, Integer exitCode, String sessionId) {
    }

    private final IdeaMcpClient client;

    @Inject
    IdeaRunCapability(IdeaMcpClient client) {
        this.client = client;
    }

    @Override
    public String identifier() {
        return "idea";
    }

    @Override
    public ProviderHealth health() {
        ConnectionStatus current = client.status();
        if (current.state() == ConnectionState.CONNECTED) {
            return ProviderHealth.available();
        }
        return ProviderHealth.unavailable(current.state().name().toLowerCase() + ": " + current.detail());
    }

    @Override
    public String provenance() {
        return "IDEA MCP run configurations";
    }

    @Override
    public CapabilityResult<List<RunConfiguration>> listConfigurations() {
        String projectPath = client.projectPath();
        Map<String, Object> arguments = projectPath == null
                ? Map.of()
                : Map.of("projectPath", projectPath);
        IdeaToolCall call = client.callIdeaTool("get_run_configurations", arguments);
        if (call.isError()) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot list run configurations", call.resultText()));
        }
        try {
            McpJsonMapper mapper = client.jsonMapper();
            IdeaRunConfigResponse response = mapper.readValue(call.resultText(), IdeaRunConfigResponse.class);
            List<RunConfiguration> result = new ArrayList<>();
            if (response.configurations() != null) {
                for (IdeaRunConfig config : response.configurations()) {
                    if (config.name() == null || config.name().isBlank()) {
                        continue;
                    }
                    result.add(new RunConfiguration(config.name(),
                            config.description() == null ? "" : config.description()));
                }
            }
            return CapabilityResult.ok(List.copyOf(result));
        } catch (Exception failure) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot parse run configurations", failure.getMessage()));
        }
    }

    @Override
    public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> outputLines) {
        return start(request, line -> outputLines.accept(line),
                completion -> outputLines.accept(completion.summary()));
    }

    @Override
    public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> outputLines,
            Consumer<RunCompletion> completion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outputLines, "outputLines");
        Objects.requireNonNull(completion, "completion");
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("configurationName", request.configurationName());
        String projectPath = client.projectPath();
        if (projectPath != null) {
            arguments.put("projectPath", projectPath);
        }
        if (request.workingDirectory() != null) {
            arguments.put("workingDirectory", request.workingDirectory().toString());
        }
        if (request.environment() != null && !request.environment().isEmpty()) {
            arguments.put("envs", request.environment());
        }
        IdeaToolCall call = client.callIdeaTool("execute_run_configuration", arguments);
        if (call.isError()) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot start run configuration", call.resultText()));
        }
        try {
            McpJsonMapper mapper = client.jsonMapper();
            IdeaRunResult result = mapper.readValue(call.resultText(), IdeaRunResult.class);
            String output = result.output() == null ? "" : result.output();
            for (String line : output.split("\\r?\\n", -1)) {
                if (!line.isEmpty()) {
                    outputLines.accept(line);
                }
            }
            if (output.isEmpty()) {
                outputLines.accept("(no output)");
            }
            completion.accept(new RunCompletion(RunOutcome.EXITED, Optional.ofNullable(result.exitCode())));
        } catch (Exception failure) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot parse run result", failure.getMessage()));
        }
        return CapabilityResult.ok(new RunHandle() {
            @Override
            public void cancel() {
                // IDEA exposes no cancel tool.
            }

            @Override
            public boolean supportsCancellation() {
                return false;
            }
        });
    }
}
