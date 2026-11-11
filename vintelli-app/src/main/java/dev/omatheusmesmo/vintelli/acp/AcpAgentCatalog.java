package dev.omatheusmesmo.vintelli.acp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.Agent;
import dev.omatheusmesmo.vintelli.web.AcpProbeConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Resolves configured agent executables without starting an agent during discovery. */
@Singleton
public final class AcpAgentCatalog {

    private final List<Launch> launches;
    private final List<Agent> agents;
    private final String defaultAgent;

    @Inject
    AcpAgentCatalog(AcpAgentsConfig config, AcpProbeConfig probe) {
        this(config, probe, System.getenv(), System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"));
    }

    AcpAgentCatalog(AcpAgentsConfig config, AcpProbeConfig probe, Map<String, String> environment, boolean windows) {
        List<Launch> choices = new ArrayList<>();
        config.agents().forEach((id, entry) -> choices.add(new Launch(id, entry.name(), entry.command(), entry.args().orElse(List.of()), config.enabled())));
        boolean legacy = probe.enabled().orElse(false) && !probe.agentCommand().orElse("").isBlank();
        if (legacy) choices.add(new Launch("configured", "Configured agent", probe.agentCommand().orElseThrow(),
                probe.agentArgs().map(value -> List.of(value.split(","))).orElse(List.of()), true));
        defaultAgent = legacy && !config.enabled() ? "configured" : config.defaultAgent();
        choices.sort(Comparator.comparing((Launch launch) -> !launch.id().equals(defaultAgent)).thenComparing(Launch::id));
        launches = List.copyOf(choices);
        agents = launches.stream().map(launch -> {
            String reason = !launch.enabled() ? "Enable vintelli.acp.enabled to start agents"
                    : executable(launch.command(), environment, windows) ? "" : "Executable not found: " + launch.command();
            return new Agent(launch.id(), launch.name(), reason.isEmpty(), reason);
        }).toList();
    }

    public List<Agent> agents() {
        return agents;
    }

    public String defaultAgent() {
        return defaultAgent;
    }

    Launch require(String id) {
        Agent agent = agents.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown ACP agent: " + id));
        if (!agent.available()) throw new IllegalStateException(agent.reason());
        return launches.stream().filter(launch -> launch.id().equals(id)).findFirst().orElseThrow();
    }

    static boolean executable(String command, Map<String, String> environment, boolean windows) {
        if (command.isBlank()) return false;
        try {
            Path path = Path.of(command);
            String extensions = environment.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
            if (path.isAbsolute() || command.contains("/") || command.contains("\\")) return executable(path, windows, extensions);
            for (String directory : environment.getOrDefault("PATH", "").split(Pattern.quote(windows ? ";" : File.pathSeparator))) {
                if (directory.isBlank()) continue;
                Path candidate = Path.of(directory).resolve(command);
                if (executable(candidate, windows, extensions)) return true;
            }
        } catch (InvalidPathException invalid) {
            return false;
        }
        return false;
    }

    private static boolean executable(Path path, boolean windows) {
        return Files.isRegularFile(path) && (windows || Files.isExecutable(path));
    }

    private static boolean executable(Path path, boolean windows, String extensions) {
        if (executable(path, windows)) return true;
        if (windows) {
            for (String extension : extensions.split(";")) {
                if (!extension.isBlank() && executable(Path.of(path + extension.toLowerCase(Locale.ROOT)), true)) return true;
                if (!extension.isBlank() && executable(Path.of(path + extension.toUpperCase(Locale.ROOT)), true)) return true;
            }
        }
        return false;
    }

    record Launch(String id, String name, String command, List<String> args, boolean enabled) {
        Launch {
            args = List.copyOf(args);
        }
    }
}
