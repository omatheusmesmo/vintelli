package dev.omatheusmesmo.vintelli.idea;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Unremovable
@Singleton
public final class IdeaDiagnosticsCapability implements DiagnosticsCapability {

    private record IdeaProblem(String severity, String description, String lineContent,
            int line, int column) {
    }

    private record IdeaProblemsResponse(String filePath, List<IdeaProblem> errors) {
    }

    private final IdeaMcpClient client;

    @Inject
    IdeaDiagnosticsCapability(IdeaMcpClient client) {
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
        return "IDEA MCP inspections";
    }

    @Override
    public CapabilityResult<List<Diagnostic>> diagnostics(Path file) {
        if (file == null) {
            return CapabilityResult.failed(new CapabilityFailure("No file given", "file is null"));
        }
        String relative = relativize(file);
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("filePath", relative);
        String projectPath = client.projectPath();
        if (projectPath != null) {
            arguments.put("projectPath", projectPath);
        }
        IdeaToolCall call = client.callIdeaTool("get_file_problems", arguments);
        if (call.isError()) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot get problems", call.resultText()));
        }
        try {
            McpJsonMapper mapper = client.jsonMapper();
            IdeaProblemsResponse response = mapper.readValue(call.resultText(), IdeaProblemsResponse.class);
            List<Diagnostic> result = new ArrayList<>();
            List<IdeaProblem> errors = response.errors() == null ? List.of() : response.errors();
            for (IdeaProblem problem : errors) {
                int line = problem.line() <= 0 ? 1 : problem.line();
                int column = problem.column() <= 0 ? 1 : problem.column();
                result.add(new Diagnostic(file,
                        Math.max(0, line - 1), Math.max(0, column - 1),
                        Math.max(0, line - 1), Math.max(0, column),
                        mapSeverity(problem.severity()), "idea",
                        problem.description() == null ? "" : problem.description()));
            }
            return CapabilityResult.ok(List.copyOf(result));
        } catch (Exception failure) {
            return CapabilityResult.failed(new CapabilityFailure("Cannot parse problems", failure.getMessage()));
        }
    }

    private String relativize(Path file) {
        String projectPath = client.projectPath();
        if (projectPath != null) {
            try {
                Path base = Paths.get(projectPath);
                if (file.startsWith(base)) {
                    return base.relativize(file).toString();
                }
            } catch (Exception ignored) {
            }
        }
        return file.toString();
    }

    private static DiagnosticsCapability.Severity mapSeverity(String severity) {
        String upper = severity == null ? "" : severity.toUpperCase();
        if (upper.contains("ERROR")) {
            return DiagnosticsCapability.Severity.ERROR;
        }
        if (upper.contains("WARNING")) {
            return DiagnosticsCapability.Severity.WARNING;
        }
        if (upper.contains("INFO")) {
            return DiagnosticsCapability.Severity.INFORMATION;
        }
        return DiagnosticsCapability.Severity.HINT;
    }
}
