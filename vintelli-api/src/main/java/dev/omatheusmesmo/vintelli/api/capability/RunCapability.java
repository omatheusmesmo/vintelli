package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runs build tool goals, tasks or run configurations and streams the output
 * lines live into the caller supplied consumer. Providers are the local
 * process runner, an LSP based server or IDEA run configurations.
 */
public interface RunCapability extends ProviderDescriptor {

    /**
     * Lists everything this provider can run by name: detected Maven goals or
     * Gradle tasks and, when available, the run configurations of the optional
     * IDEA provider. Callers pick one identifier and pass it to
     * {@link #start(RunRequest, Consumer)}.
     */
    CapabilityResult<List<RunConfiguration>> listConfigurations();

    /**
     * Starts the run described by the request. Output lines stream into the
     * consumer while the run executes.
     *
     * @param request what to run and where
     * @param outputLines consumer that receives the output lines live, never null
     * @return a cancellation handle, or a failed result when the provider
     *         cannot start the run, for example an unknown configuration name
     */
    CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> outputLines);

    /**
     * Starts a run and reports its lifecycle through a typed completion
     * callback, separate from user process output. Existing providers may keep
     * implementing the two-argument method; providers with lifecycle data
     * should override this method.
     */
    default CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> outputLines,
            Consumer<RunCompletion> completion) {
        Objects.requireNonNull(completion, "completion");
        return start(request, outputLines);
    }

    enum RunOutcome {
        EXITED,
        CANCELLED,
        TIMED_OUT
    }

    record RunCompletion(RunOutcome outcome, Optional<Integer> exitCode) {

        public RunCompletion {
            Objects.requireNonNull(outcome, "outcome");
            exitCode = exitCode == null ? Optional.empty() : exitCode;
        }

        public String summary() {
            return switch (outcome) {
                case EXITED -> "Run finished with exit code " + exitCode.map(String::valueOf).orElse("unknown");
                case CANCELLED -> "Run cancelled";
                case TIMED_OUT -> "Run timed out";
            };
        }
    }

    record RunConfiguration(String identifier, String summary) {

        public RunConfiguration {
            if (identifier == null || identifier.isBlank()) {
                throw new IllegalArgumentException("A run configuration needs a non blank identifier");
            }
            summary = summary == null ? "" : summary;
        }
    }

    record RunRequest(String configurationName, Path workingDirectory, Map<String, String> environment) {

        public RunRequest {
            if (configurationName == null || configurationName.isBlank()) {
                throw new IllegalArgumentException("A run request always names what to run");
            }
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }

    /**
     * Handle of one started run. After {@link #cancel()} the provider stops the
     * run as soon as it can and reports any remaining output through the
     * stream consumer of the call that created the handle.
     */
    interface RunHandle {

        /**
         * Asks the provider to stop the run. Calling it on an already finished
         * run is a harmless no op.
         */
        void cancel();

        /**
         * Whether the provider can stop an active run. Snapshot providers may
         * return a handle for API compatibility while exposing no cancellation.
         */
        default boolean supportsCancellation() {
            return true;
        }
    }
}
