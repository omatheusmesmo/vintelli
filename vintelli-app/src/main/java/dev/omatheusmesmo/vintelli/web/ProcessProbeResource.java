package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.local.LocalProjectRoot;
import dev.omatheusmesmo.vintelli.local.LocalRunCapability;
import dev.omatheusmesmo.vintelli.local.ProcessRunner;
import io.smallrye.common.annotation.Blocking;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Headless probe of {@link ProcessRunner} for the native image: runs a fixed
 * short command that succeeds with output, a fixed command that outlives its
 * timeout (keeping the partial output) and checks that a working directory
 * outside {@code vintelli.local.project-root} is refused. No request input is
 * accepted, so the inspect gate stays a test and debugging surface rather
 * than an agent reachable process endpoint.
 *
 * <p>The endpoint is off unless {@code vintelli.inspect.enabled} is
 * {@code true} and answers 404 otherwise.
 */
@Path("/tui")
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public final class ProcessProbeResource {

    private static final Duration SUCCESS_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration TIMEOUT_LIMIT = Duration.ofSeconds(3);

    private final ProcessRunner runner;
    private final LocalProjectRoot projectRoot;
    private final LocalRunCapability runCapability;
    private final boolean enabled;

    @Inject
    public ProcessProbeResource(ProcessRunner runner, LocalProjectRoot projectRoot,
            LocalRunCapability runCapability,
            @ConfigProperty(name = "vintelli.inspect.enabled", defaultValue = "false") boolean enabled) {
        this.runner = runner;
        this.projectRoot = projectRoot;
        this.runCapability = runCapability;
        this.enabled = enabled;
    }

    @POST
    @Path("/process-probe")
    @Blocking
    public ProcessProbeReply probe() {
        if (!enabled) {
            throw new NotFoundException();
        }
        String successOutput = "";
        String timeoutOutput = "";
        String refusal = "";
        String detail = "";
        try {
            var work = projectRoot.canonical();
            ProcessRunner.ProcessResult success = runner.run(new ProcessRunner.CommandPlan(
                    successCommand(), work, Map.of(), SUCCESS_TIMEOUT));
            successOutput = success.output();

            ProcessRunner.ProcessResult timed = runner.run(new ProcessRunner.CommandPlan(
                    timeoutCommand(), work, Map.of(), TIMEOUT_LIMIT));
            timeoutOutput = timed.output();

            var candidate = projectRoot.raw().toAbsolutePath().getParent();
            if (candidate == null) {
                candidate = projectRoot.raw().getFileSystem().getPath(System.getProperty("java.io.tmpdir")).toAbsolutePath();
            }
            CapabilityResult<RunCapability.RunHandle> result = runCapability.start(
                    new RunCapability.RunRequest("probe", candidate, Map.of()), ignored -> {
                    });
            refusal = result.failureOf().map(CapabilityFailure::reason).orElse("");
        } catch (Exception failure) {
            detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        return new ProcessProbeReply("OK", successOutput, timeoutOutput, refusal, detail);
    }

    /**
     * Outcome of the probe: overall state, the output of the successful
     * command, the partial output of the timed out command, whether a working
     * directory outside the project root was refused (the refusal reason, empty
     * when it was not refused) and any exception detail.
     */
    public record ProcessProbeReply(String state, String successOutput, String timeoutOutput,
            String refusal, String detail) {
    }

    private static List<String> successCommand() {
        if (isWindows()) {
            return List.of("cmd", "/c", "echo vintelli-process-probe-ok");
        }
        return List.of("sh", "-c", "echo vintelli-process-probe-ok");
    }

    private static List<String> timeoutCommand() {
        if (isWindows()) {
            return List.of("cmd", "/c", "echo vintelli-process-probe-timeout && ping -n 30 127.0.0.1 > nul");
        }
        return List.of("sh", "-c", "echo vintelli-process-probe-timeout; sleep 60");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
