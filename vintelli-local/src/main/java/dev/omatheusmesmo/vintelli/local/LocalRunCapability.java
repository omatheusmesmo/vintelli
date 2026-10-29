package dev.omatheusmesmo.vintelli.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Local process runner of the run capability: lists the lifecycle goals and
 * tasks of the detected build tool as run configurations and streams every
 * output line into the consumer passed by the caller while the process runs.
 * The caller decides what a line means at the user interface level, for
 * example appending it to the run buffer on the render thread; this provider
 * only delivers lines in order.
 *
 * <p>Every run carries a timeout configured with
 * {@code vintelli.local.process-timeout} and is stopped through the returned
 * handle by terminating the whole process tree. The command reaches the
 * child process as an argument list, never through a shell.
 */
@Unremovable
@Singleton
public final class LocalRunCapability implements RunCapability {

    private final LocalProjectRoot projectRoot;
    private final ProcessRunner runner;
    private final BuildToolDetector detector;
    private final Duration defaultTimeout;

    @Inject
    LocalRunCapability(LocalProjectRoot projectRoot, ProcessRunner runner,
            @ConfigProperty(name = "vintelli.local.process-timeout", defaultValue = "60M") Duration timeout) {
        this.projectRoot = projectRoot;
        this.runner = runner;
        this.detector = new BuildToolDetector(projectRoot);
        this.defaultTimeout = timeout;
    }

    private LocalRunCapability(LocalProjectRoot projectRoot, ProcessRunner runner,
            BuildToolDetector detector, Duration timeout) {
        this.projectRoot = projectRoot;
        this.runner = runner;
        this.detector = detector;
        this.defaultTimeout = timeout;
    }

    /**
     * Non CDI factory for tests: the wiring of local operations is plain
     * constructor data in this module, never a boundary of the container.
     */
    public static LocalRunCapability of(LocalProjectRoot projectRoot, Duration timeout) {
        return new LocalRunCapability(projectRoot, new ProcessRunner(),
                new BuildToolDetector(projectRoot), timeout);
    }

    @Override
    public String identifier() {
        return "local";
    }

    @Override
    public ProviderHealth health() {
        return ProviderHealth.available();
    }

    @Override
    public String provenance() {
        return switch (detector.detectedTool()) {
            case MAVEN, GRADLE -> "local process runner via " + detector.programLabel();
            case NONE -> "local process runner, no build tool detected";
        };
    }

    @Override
    public CapabilityResult<List<RunConfiguration>> listConfigurations() {
        List<RunConfiguration> configurations = new ArrayList<>();
        for (BuildToolDetector.RunConfigurationSpecification specification : detector.specifications()) {
            configurations.add(new RunConfiguration(specification.identifier(), summary(specification)));
        }
        return CapabilityResult.ok(List.copyOf(configurations));
    }

    private String summary(BuildToolDetector.RunConfigurationSpecification specification) {
        String goal = specification.identifier().split(":", 2)[1];
        return "Runs " + goal + " with " + detector.programLabel();
    }

    @Override
    public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> outputLines) {
        Path confined = projectRoot.confine(request.workingDirectory());
        if (confined == null) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "The working directory is outside the project root or cannot be resolved",
                    request.workingDirectory().toString()));
        }
        if (!Files.isDirectory(confined)) {
            return CapabilityResult.failed(new CapabilityFailure("The working directory does not exist",
                    request.workingDirectory().toString()));
        }
        for (BuildToolDetector.RunConfigurationSpecification specification : detector.specifications()) {
            if (specification.identifier().equals(request.configurationName())) {
                return run(specification, request, outputLines, confined);
            }
        }
        return CapabilityResult.failed(new CapabilityFailure(
                "Unknown run configuration '" + request.configurationName() + "'",
                "Available names: " + listedConfigurationNames()));
    }

    private CapabilityResult<RunHandle> run(BuildToolDetector.RunConfigurationSpecification specification,
            RunRequest request, Consumer<String> outputLines, Path workingDirectory) {
        try {
            ProcessRunner.Handle handle = runner.start(new ProcessRunner.CommandPlan(specification.command(),
                    workingDirectory, request.environment(), defaultTimeout), outputLines,
                    termination -> outputLines.accept(summaryLine(termination)));
            return CapabilityResult.ok(handle::cancel);
        } catch (ProcessRunner.UncheckedProcessFailure failure) {
            return CapabilityResult.failed(
                    new CapabilityFailure("Cannot start the run", failure.getMessage()));
        }
    }

    static String summaryLine(ProcessRunner.ProcessTermination termination) {
        return switch (termination.outcome()) {
            case EXITED -> "Run finished with exit code "
                    + termination.exitCode().map(String::valueOf).orElse("unknown");
            case CANCELLED -> "Run cancelled";
            case TIMED_OUT -> "Run timed out";
        };
    }

    private String listedConfigurationNames() {
        List<String> names = new ArrayList<>();
        for (BuildToolDetector.RunConfigurationSpecification specification : detector.specifications()) {
            names.add(specification.identifier());
        }
        return names.isEmpty() ? "none, no build tool detected" : String.join(", ", names);
    }
}
