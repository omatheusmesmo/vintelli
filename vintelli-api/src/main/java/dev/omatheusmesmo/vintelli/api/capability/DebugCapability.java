package dev.omatheusmesmo.vintelli.api.capability;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Starts or attaches to a debug session and exposes its state without leaking
 * a debugger protocol implementation into the application API. A session
 * created by {@link #launch(DebugRequest, Consumer)} owns its run handle and
 * stops that run when disconnected or cancelled. A session created by
 * {@link #attach(AttachRequest, Consumer)} only detaches from the existing
 * loopback debuggee and never terminates that process.
 */
public interface DebugCapability extends ProviderDescriptor {

    /**
     * Starts the requested run suspended, attaches a debugger and returns once
     * the session is configured.
     *
     * @param request run configuration and project directory
     * @param outputLines receives output from the debuggee
     * @return the live session, or a failure with a reason the caller can show
     */
    CapabilityResult<DebugSession> launch(DebugRequest request, Consumer<String> outputLines);

    /**
     * Attaches to a debuggee already listening on loopback. The request carries
     * no host field, so implementations cannot connect to a remote address.
     *
     * @param request target port and display label
     * @param outputLines receives output from the debuggee when the adapter provides it
     * @return the live session, or a failure with a reason the caller can show
     */
    CapabilityResult<DebugSession> attach(AttachRequest request, Consumer<String> outputLines);

    /**
     * @return immutable snapshots of the sessions currently retained by the provider
     */
    List<DebugSessionSnapshot> snapshot();

    /**
     * Replaces the complete breakpoint set for one source file in every live session.
     * Lines use the DAP convention and are one based. The returned locations include
     * adapter verification state; adapters that have not answered remain pending.
     */
    default CapabilityResult<List<SourceBreakpoint>> setBreakpoints(String sourcePath,
            List<SourceBreakpoint> breakpoints) {
        return CapabilityResult.failed(new CapabilityFailure("Source breakpoints are not supported", sourcePath));
    }

    /**
     * Reconciles the provider's complete source snapshot. Sources previously
     * known by the provider but omitted from this snapshot are cleared.
     */
    default CapabilityResult<Map<String, List<SourceBreakpoint>>> replaceBreakpoints(
            Map<String, List<SourceBreakpoint>> completeSources) {
        Map<String, List<SourceBreakpoint>> result = new java.util.TreeMap<>();
        if (completeSources != null) {
            for (Map.Entry<String, List<SourceBreakpoint>> source : completeSources.entrySet()) {
                CapabilityResult<List<SourceBreakpoint>> applied = setBreakpoints(source.getKey(), source.getValue());
                if (applied instanceof CapabilityResult.Failed<List<SourceBreakpoint>> failed) {
                    return CapabilityResult.failed(failed.failure());
                }
                result.put(source.getKey(), ((CapabilityResult.Ok<List<SourceBreakpoint>>) applied).value());
            }
        }
        return CapabilityResult.ok(Map.copyOf(result));
    }

    enum State {
        STARTING,
        ATTACHING,
        EXECUTING,
        STOPPED,
        RUNNING,
        TERMINATED,
        DISCONNECTED,
        FAILED
    }

    enum SteppingAction { RESUME, PAUSE, STEP_OVER, STEP_INTO, STEP_OUT }

    record DebugFrame(String name, String sourcePath, int line, int column) {
        public DebugFrame {
            name = name == null ? "" : name;
            sourcePath = sourcePath == null ? "" : sourcePath;
            if (line < 1 || column < 1) throw new IllegalArgumentException("DAP frame locations are one based");
        }
    }

    record DebugCapabilities(boolean resume, boolean pause, boolean stepOver, boolean stepInto, boolean stepOut,
            boolean pauseKnown) {
        public DebugCapabilities(boolean resume, boolean pause, boolean stepOver, boolean stepInto, boolean stepOut) {
            this(resume, pause, stepOver, stepInto, stepOut, true);
        }

        public boolean supports(SteppingAction action) {
            return switch (action) {
                case RESUME -> resume;
                case PAUSE -> pauseKnown && pause;
                case STEP_OVER -> stepOver;
                case STEP_INTO -> stepInto;
                case STEP_OUT -> stepOut;
            };
        }

        public boolean known(SteppingAction action) {
            return action != SteppingAction.PAUSE || pauseKnown;
        }
    }

    enum BreakpointState { PENDING, VERIFIED, REJECTED }

    record SourceBreakpoint(String sourcePath, int line, BreakpointState state, String message,
            Integer resolvedLine, String resolvedSourcePath) {
        public SourceBreakpoint {
            if (sourcePath == null || sourcePath.isBlank()) {
                throw new IllegalArgumentException("Breakpoint source path is required");
            }
            sourcePath = sourcePath.trim();
            if (line < 1) {
                throw new IllegalArgumentException("Breakpoint lines are one based");
            }
            if (resolvedLine != null && resolvedLine < 1) {
                throw new IllegalArgumentException("Resolved breakpoint lines are one based");
            }
            state = state == null ? BreakpointState.PENDING : state;
            message = message == null ? "" : message;
            resolvedSourcePath = resolvedSourcePath == null || resolvedSourcePath.isBlank()
                    ? sourcePath : resolvedSourcePath.trim();
        }

        public SourceBreakpoint(String sourcePath, int line) {
            this(sourcePath, line, BreakpointState.PENDING, "", null, sourcePath);
        }

        public SourceBreakpoint(String sourcePath, int line, BreakpointState state, String message) {
            this(sourcePath, line, state, message, null, sourcePath);
        }
    }

    record DebugRequest(RunCapability.RunRequest runRequest, String label) {

        public DebugRequest {
            Objects.requireNonNull(runRequest, "runRequest");
            label = label == null || label.isBlank() ? runRequest.configurationName() : label.trim();
        }
    }

    record AttachRequest(int port, String label) {

        public AttachRequest {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Debug port must be between 1 and 65535");
            }
            label = label == null || label.isBlank() ? "127.0.0.1:" + port : label.trim();
        }
    }

    record DebugThread(int id, String name) {

        public DebugThread {
            name = name == null ? "" : name;
        }
    }

    record DebugSessionSnapshot(String id, String configurationName, State state,
            List<DebugThread> threads, String reason, String stoppedReason,
            int stoppedThreadId, DebugFrame currentFrame, DebugCapabilities capabilities) {

        public DebugSessionSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(configurationName, "configurationName");
            Objects.requireNonNull(state, "state");
            threads = threads == null ? List.of() : List.copyOf(threads);
            reason = reason == null ? "" : reason;
            stoppedReason = stoppedReason == null ? "" : stoppedReason;
            capabilities = capabilities == null ? new DebugCapabilities(false, false, false, false, false, true) : capabilities;
        }

        public DebugSessionSnapshot(String id, String configurationName, State state,
                List<DebugThread> threads, String reason, String stoppedReason) {
            this(id, configurationName, state, threads, reason, stoppedReason, 0, null,
                    new DebugCapabilities(false, false, false, false, false, true));
        }
    }

    interface DebugSession {

        DebugSessionSnapshot snapshot();

        CapabilityResult<List<DebugThread>> threads();

        default CapabilityResult<Boolean> resume() { return unsupported("Resume"); }
        default CapabilityResult<Boolean> pause() { return unsupported("Pause"); }
        default CapabilityResult<Boolean> stepOver() { return unsupported("Step over"); }
        default CapabilityResult<Boolean> stepInto() { return unsupported("Step into"); }
        default CapabilityResult<Boolean> stepOut() { return unsupported("Step out"); }

        private static CapabilityResult<Boolean> unsupported(String action) {
            return CapabilityResult.failed(new CapabilityFailure(action + " is not supported", ""));
        }

        /**
         * Detaches from the adapter. A launch-owned run is cancelled as part of
         * cleanup; an externally started debuggee remains running.
         */
        CapabilityResult<Boolean> disconnect();

        /** Requests the same ownership-aware cleanup as {@link #disconnect()}. */
        void cancel();
    }
}
