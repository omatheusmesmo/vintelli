package dev.omatheusmesmo.vintelli.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.local.LocalProjectRoot;
import dev.omatheusmesmo.vintelli.local.LocalRunCapability;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Fixed inspect-only native probe: three known Maven JDWP launches, with no command or argument input. */
@Path("/tui/debug-launch-probe")
@Singleton
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public final class DebugLaunchProbeResource {
    private final LocalRunCapability runs;
    private final LocalProjectRoot root;
    private final boolean enabled;
    private final int port;
    private final List<String> output = new CopyOnWriteArrayList<>();
    private final AtomicReference<RunCapability.RunCompletion> completion = new AtomicReference<>();
    private RunCapability.RunHandle handle;
    private String failure = "";

    @Inject
    public DebugLaunchProbeResource(LocalRunCapability runs, LocalProjectRoot root,
            @ConfigProperty(name = "vintelli.inspect.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "vintelli.debug.port", defaultValue = "5005") int port) {
        this.runs = runs;
        this.root = root;
        this.enabled = enabled;
        this.port = port;
    }

    @POST
    public synchronized View start(@QueryParam("kind") @DefaultValue("test") String kind) {
        checkEnabled();
        if (handle != null && completion.get() == null) return snapshot();
        String configuration = switch (kind) {
            case "test" -> "maven:test";
            case "quarkus" -> "maven:quarkus:dev";
            case "spring" -> "maven:spring-boot:run";
            default -> throw new BadRequestException("Unknown debug probe kind");
        };
        output.clear();
        completion.set(null);
        failure = "";
        var result = runs.startDebug(new RunCapability.RunRequest(configuration, root.canonical(), Map.of()), port,
                line -> { if (output.size() < 200) output.add(line); }, completion::set);
        if (result instanceof CapabilityResult.Ok<RunCapability.RunHandle> started) handle = started.value();
        else {
            handle = null;
            failure = ((CapabilityResult.Failed<RunCapability.RunHandle>) result).failure().reason();
        }
        return snapshot();
    }

    @GET
    public synchronized View get() {
        checkEnabled();
        return snapshot();
    }

    @DELETE
    public synchronized View stop() {
        checkEnabled();
        cancel();
        return snapshot();
    }

    @PreDestroy
    void cancel() {
        if (handle != null) handle.cancel();
    }

    private View snapshot() {
        return new View("127.0.0.1", port, List.copyOf(output), failure,
                completion.get() == null ? "" : completion.get().summary());
    }

    private void checkEnabled() {
        if (!enabled) throw new NotFoundException();
    }

    public record View(String host, int port, List<String> output, String failure, String completion) { }
}
