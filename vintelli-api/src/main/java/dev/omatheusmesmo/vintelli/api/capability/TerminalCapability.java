package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Executes a terminal command line, as a local shell or as a command executed
 * inside the optional IDEA terminal. A provider may refuse to run a command it
 * considers dangerous and ask for explicit confirmation instead: the caller
 * reissues the request with {@link TerminalRequest#confirmed()} set.
 */
public interface TerminalCapability extends ProviderDescriptor {

    /**
     * @param request command to execute, working directory and whether the
     *                caller already confirmed it
     * @return the command outcome, or a failed result when the provider cannot
     *         execute commands, for example when its shell is not running
     */
    CapabilityResult<TerminalResult> execute(TerminalRequest request);

    record TerminalRequest(String commandLine, Path workingDirectory, boolean confirmed) {

        public TerminalRequest {
            if (commandLine == null || commandLine.isBlank()) {
                throw new IllegalArgumentException("A terminal request always carries a command line");
            }
            Objects.requireNonNull(workingDirectory, "workingDirectory");
        }
    }

    record TerminalResult(TerminalOutcome outcome, int exitCode, String output) {

        public TerminalResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(output, "output");
        }
    }

    /**
     * Outcome of one terminal command. {@code REQUIRES_CONFIRMATION} carries no
     * exit code and no output: the provider ran nothing and waits for a
     * confirmed reissue of the request.
     */
    enum TerminalOutcome {
        EXECUTED,
        REQUIRES_CONFIRMATION
    }
}
