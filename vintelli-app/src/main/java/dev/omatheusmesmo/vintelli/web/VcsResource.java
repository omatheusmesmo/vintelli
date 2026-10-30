package dev.omatheusmesmo.vintelli.web;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Headless anchor for the version control provider, so the integration tests,
 * including the native runner, can read the Git state of the project over HTTP
 * instead of through the TUI VCS menu. The answer resolves the provider
 * through the {@link CapabilityRegistry}: the same selection the status bar
 * and the tool windows consume. A selected provider maps its results to the
 * answer, a selection without a healthy provider answers 503 with its reason
 * and a failed provider result answers 409 with the provider's own reason, so
 * the explicit failures (not a repository, a path outside the repository)
 * never look like a broken endpoint.
 *
 * <p>The endpoint is off unless {@code vintelli.vcs.debug-endpoint.enabled} is
 * {@code true} and answers 404 otherwise. Requested diff paths are confined by
 * the provider itself: they resolve against the project root and anything that
 * lands outside the project root comes back as a failed result, and the status
 * entries are the changed paths under the project root only.
 */
@Path("/vcs")
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public final class VcsResource {

    private static final int MAX_FILE_PARAMETER_LENGTH = 1024;

    private final CapabilityRegistry registry;
    private final boolean enabled;

    @Inject
    VcsResource(CapabilityRegistry registry, VcsDebugConfig config) {
        this.registry = registry;
        this.enabled = config.enabled().orElse(false);
    }

    @GET
    @Path("/status")
    @Blocking
    public RestResponse<VcsStatusReply> status() {
        if (!enabled) {
            return RestResponse.status(RestResponse.Status.NOT_FOUND, VcsStatusReply.disabled());
        }
        CapabilitySelection<VersionControlCapability> selection = registry.versionControl();
        if (!selection.isAvailable()) {
            return RestResponse.status(RestResponse.Status.SERVICE_UNAVAILABLE, VcsStatusReply.unavailable(
                    ((CapabilitySelection.Unavailable<VersionControlCapability>) selection).reason()));
        }
        return asStatusReply(((CapabilitySelection.Selected<VersionControlCapability>) selection)
                .provider()
                .status());
    }

    @GET
    @Path("/diff")
    @Blocking
    public RestResponse<VcsDiffReply> diff(@QueryParam("file") String file) {
        if (!enabled) {
            return RestResponse.status(RestResponse.Status.NOT_FOUND, VcsDiffReply.disabled());
        }
        if (file == null || file.isBlank()) {
            return RestResponse.status(RestResponse.Status.BAD_REQUEST,
                    new VcsDiffReply("REJECTED", "file is required", ""));
        }
        if (file.length() > MAX_FILE_PARAMETER_LENGTH) {
            return RestResponse.status(RestResponse.Status.BAD_REQUEST,
                    new VcsDiffReply("REJECTED", "file is too long", ""));
        }
        java.nio.file.Path requested;
        try {
            requested = FileSystems.getDefault().getPath(file);
        } catch (InvalidPathException invalid) {
            return RestResponse.status(RestResponse.Status.BAD_REQUEST,
                    new VcsDiffReply("REJECTED", "invalid path", ""));
        }
        CapabilitySelection<VersionControlCapability> selection = registry.versionControl();
        if (!selection.isAvailable()) {
            return RestResponse.status(RestResponse.Status.SERVICE_UNAVAILABLE, VcsDiffReply.unavailable(
                    ((CapabilitySelection.Unavailable<VersionControlCapability>) selection).reason()));
        }
        return asDiffReply(((CapabilitySelection.Selected<VersionControlCapability>) selection)
                .provider()
                .diff(requested));
    }

    private static RestResponse<VcsStatusReply> asStatusReply(CapabilityResult<VersionControlCapability.Status> result) {
        return switch (result) {
            case CapabilityResult.Failed<VersionControlCapability.Status> failed ->
                    RestResponse.status(RestResponse.Status.CONFLICT, VcsStatusReply.failed(failed.failure()));
            case CapabilityResult.Ok<VersionControlCapability.Status> ok ->
                    RestResponse.ok(VcsStatusReply.ok(ok.value()));
        };
    }

    private static RestResponse<VcsDiffReply> asDiffReply(CapabilityResult<String> result) {
        return switch (result) {
            case CapabilityResult.Failed<String> failed ->
                    RestResponse.status(RestResponse.Status.CONFLICT, VcsDiffReply.failed(failed.failure()));
            case CapabilityResult.Ok<String> ok ->
                    RestResponse.ok(VcsDiffReply.ok(ok.value()));
        };
    }

    /**
     * Answer of {@code GET /vcs/status}: the classified state
     * ({@code OK}, {@code FAILED}, {@code UNAVAILABLE} or {@code DISABLED}),
     * the provider failure or selection detail when there is one, the
     * {@code truncated} marker that tells a bounded entry list apart from the
     * complete one and the {@link VersionControlCapability.Status} payload.
     */
    public record VcsStatusReply(String state, String detail, String branch, Integer ahead, Integer behind,
            boolean truncated, List<VcsEntryReply> entries) {

        private static VcsStatusReply ok(VersionControlCapability.Status status) {
            List<VcsEntryReply> entries = status.entries().stream()
                    .map(entry -> new VcsEntryReply(entry.state().name(), entry.path()))
                    .toList();
            return new VcsStatusReply("OK", "", status.branch(), status.ahead(), status.behind(),
                    status.truncated(), entries);
        }

        private static VcsStatusReply failed(CapabilityFailure failure) {
            return new VcsStatusReply("FAILED", failureDetail(failure), null, null, null, false, List.of());
        }

        private static VcsStatusReply unavailable(String reason) {
            return new VcsStatusReply("UNAVAILABLE", reason, null, null, null, false, List.of());
        }

        private static VcsStatusReply disabled() {
            return new VcsStatusReply("DISABLED", "", null, null, null, false, List.of());
        }
    }

    /**
     * One flattened worktree state of the status answer, because the port's
     * enum serializes by name.
     */
    public record VcsEntryReply(String state, String path) {
    }

    /**
     * Answer of {@code GET /vcs/diff}: the classified state, the provider
     * failure or selection detail when there is one and the raw unified diff
     * text.
     */
    public record VcsDiffReply(String state, String detail, String text) {

        private static VcsDiffReply ok(String diff) {
            return new VcsDiffReply("OK", "", diff);
        }

        private static VcsDiffReply failed(CapabilityFailure failure) {
            return new VcsDiffReply("FAILED", failureDetail(failure), "");
        }

        private static VcsDiffReply unavailable(String reason) {
            return new VcsDiffReply("UNAVAILABLE", reason, "");
        }

        private static VcsDiffReply disabled() {
            return new VcsDiffReply("DISABLED", "", "");
        }
    }

    private static String failureDetail(CapabilityFailure failure) {
        return failure.detail().isBlank()
                ? failure.reason()
                : failure.reason() + ": " + failure.detail();
    }
}
