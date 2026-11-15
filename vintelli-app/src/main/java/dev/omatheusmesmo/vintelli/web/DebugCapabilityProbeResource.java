package dev.omatheusmesmo.vintelli.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSession;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSessionSnapshot;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugFrame;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugScope;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugVariable;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.local.LocalProjectRoot;
import dev.omatheusmesmo.vintelli.tui.ToolWindowRegistry;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Fixed inspect-only DAP probe for native validation; requests cannot supply paths, commands or arguments. */
@Path("/tui/debug-capability-probe")
@Singleton
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public final class DebugCapabilityProbeResource {

    private final CapabilityRegistry registry;
    private final LocalProjectRoot projectRoot;
    private final ToolWindowRegistry toolWindows;
    private final boolean enabled;
    private final int attachPort;
    private volatile List<String> output = new CopyOnWriteArrayList<>();
    private volatile List<DebugCapability.SourceBreakpoint> breakpoints = List.of();
    private volatile List<DebugFrame> frames = List.of();
    private volatile List<DebugScope> scopes = List.of();
    private volatile List<DebugVariable> variables = List.of();
    private DebugSession session;
    private String error = "";

    @Inject
    public DebugCapabilityProbeResource(CapabilityRegistry registry, LocalProjectRoot projectRoot, ToolWindowRegistry toolWindows,
            @ConfigProperty(name = "vintelli.inspect.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "vintelli.debug.probe.attach-port", defaultValue = "5005") int attachPort) {
        this.registry = registry;
        this.projectRoot = projectRoot;
        this.toolWindows = toolWindows;
        this.enabled = enabled;
        this.attachPort = attachPort;
    }

    @POST
    public synchronized View start(@QueryParam("kind") @DefaultValue("attach") String kind) {
        checkEnabled();
        if (!kind.equals("attach") && !kind.equals("launch") && !kind.equals("breakpoints")
                && !kind.equals("stepping") && !kind.equals("inspection")) {
            throw new BadRequestException("Unknown debug probe kind");
        }
        CapabilitySelection<DebugCapability> selection = registry.debug();
        if (selection instanceof CapabilitySelection.Unavailable<DebugCapability> unavailable) {
            error = unavailable.reason();
            return view(null, false);
        }
        DebugCapability provider = ((CapabilitySelection.Selected<DebugCapability>) selection).provider();
        if (session != null) {
            session.disconnect();
            session = null;
        }
        List<String> sessionOutput = new CopyOnWriteArrayList<>();
        output = sessionOutput;
        error = "";
        breakpoints = List.of();
        frames = List.of(); scopes = List.of(); variables = List.of();
        CapabilityResult<DebugSession> result;
        if (!kind.equals("launch")) {
            result = provider.attach(new DebugCapability.AttachRequest(attachPort, "native probe attach"),
                    line -> append(sessionOutput, line));
        } else {
            RunCapability.RunRequest run = new RunCapability.RunRequest("maven:test", projectRoot.canonical(), Map.of());
            result = provider.launch(new DebugCapability.DebugRequest(run, "native probe launch"),
                    line -> append(sessionOutput, line));
        }
        if (result instanceof CapabilityResult.Failed<DebugSession> failed) {
            error = failed.failure().reason();
            return view(provider, true);
        }
        session = ((CapabilityResult.Ok<DebugSession>) result).value();
        if (kind.equals("breakpoints")) {
            String source = projectRoot.canonical().resolve("Main.java").toString();
            CapabilityResult<List<DebugCapability.SourceBreakpoint>> applied = provider.setBreakpoints(source,
                    List.of(new DebugCapability.SourceBreakpoint(source, 3)));
            if (applied instanceof CapabilityResult.Ok<List<DebugCapability.SourceBreakpoint>> ok) {
                breakpoints = ok.value();
            } else {
                error = applied.failureOf().orElseThrow().reason();
            }
        }
        CapabilityResult<List<DebugCapability.DebugThread>> threads = session.threads();
        if (threads instanceof CapabilityResult.Failed<List<DebugCapability.DebugThread>> failed) {
            error = failed.failure().reason();
        }
        if (kind.equals("stepping") || kind.equals("inspection")) {
            if (kind.equals("stepping")) {
                CapabilityResult<Boolean> paused = session.pause();
                if (paused instanceof CapabilityResult.Failed<Boolean> failed) error = failed.failure().reason();
                for (CapabilityResult<Boolean> action : List.of(session.stepOver(), session.stepInto(), session.stepOut())) {
                    if (action instanceof CapabilityResult.Failed<Boolean> failed) error = failed.failure().reason();
                }
            } else if (session.snapshot().state() != DebugCapability.State.STOPPED) {
                CapabilityResult<Boolean> paused = session.pause();
                if (paused instanceof CapabilityResult.Failed<Boolean> failed) error = failed.failure().reason();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.snapshot().currentFrame() == null && System.nanoTime() < deadline) {
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    error = "Interrupted while reading the stopped frame";
                    break;
                }
            }
            DebugSessionSnapshot stopped = session.snapshot();
            if (stopped.state() == DebugCapability.State.STOPPED && stopped.stoppedThreadId() > 0) {
                CapabilityResult<List<DebugFrame>> stack = session.stackTrace(stopped.stoppedThreadId());
                if (stack instanceof CapabilityResult.Ok<List<DebugFrame>> ok) {
                    frames = ok.value();
                    if (!frames.isEmpty()) {
                        DebugFrame frame = frames.getFirst(); session.selectFrame(frame);
                        CapabilityResult<List<DebugScope>> scopeResult = session.scopes(frame.frameId());
                        if (scopeResult instanceof CapabilityResult.Ok<List<DebugScope>> okScopes) {
                            scopes = okScopes.value();
                            if (!scopes.isEmpty() && scopes.getFirst().variablesReference() > 0) {
                                CapabilityResult<List<DebugVariable>> variableResult = session.variables(
                                        scopes.getFirst().variablesReference());
                                if (variableResult instanceof CapabilityResult.Ok<List<DebugVariable>> okVariables)
                                    variables = okVariables.value();
                                else error = variableResult.failureOf().map(failure -> failure.reason()).orElse("Variable inspection failed");
                            }
                        } else error = scopeResult.failureOf().map(failure -> failure.reason()).orElse("Scope inspection failed");
                    }
                } else error = stack.failureOf().map(failure -> failure.reason()).orElse("Stack inspection failed");
            }
        }
        return view(provider, true);
    }

    @GET
    public synchronized View inspect() {
        checkEnabled();
        CapabilitySelection<DebugCapability> selection = registry.debug();
        if (selection instanceof CapabilitySelection.Unavailable<DebugCapability> unavailable) {
            return new View(false, unavailable.reason(), null, List.of(), List.copyOf(output), List.copyOf(breakpoints),
                    List.of(), List.of(), List.of(), registeredWindows());
        }
        return view(((CapabilitySelection.Selected<DebugCapability>) selection).provider(), true);
    }

    @DELETE
    public synchronized View stop() {
        checkEnabled();
        if (session != null) {
            CapabilityResult<Boolean> result = session.disconnect();
            if (result instanceof CapabilityResult.Failed<Boolean> failed) {
                error = failed.failure().reason();
            }
        }
        return inspect();
    }

    @PreDestroy
    void cancel() {
        if (session != null) {
            session.cancel();
        }
    }

    private View view(DebugCapability provider, boolean available) {
        DebugSessionSnapshot snapshot = session == null ? null : session.snapshot();
        return new View(available, error, snapshot, provider == null ? List.of() : provider.snapshot(),
                List.copyOf(output), List.copyOf(breakpoints), List.copyOf(frames), List.copyOf(scopes), List.copyOf(variables),
                registeredWindows());
    }

    private List<String> registeredWindows() {
        return toolWindows.toolWindows().stream()
                .map(window -> window.id() + ":" + window.shortcut())
                .toList();
    }

    private void append(List<String> target, String line) {
        if (target.size() < 200) {
            target.add(line);
        }
    }

    private void checkEnabled() {
        if (!enabled) {
            throw new NotFoundException();
        }
    }

    /** Read-only result returned by the inspect-gated probe. */
    public record View(boolean available, String error, DebugSessionSnapshot snapshot,
            List<DebugSessionSnapshot> sessions, List<String> output,
            List<DebugCapability.SourceBreakpoint> breakpoints, List<DebugFrame> frames,
            List<DebugScope> scopes, List<DebugVariable> variables, List<String> toolWindows) {
    }
}
