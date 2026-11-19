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

    record DebugFrame(String name, String sourcePath, int line, int column, int frameId) {
        public DebugFrame {
            name = name == null ? "" : name;
            sourcePath = sourcePath == null ? "" : sourcePath;
            if (line < 1 || column < 1) throw new IllegalArgumentException("DAP frame locations are one based");
        }

        public DebugFrame(String name, String sourcePath, int line, int column) {
            this(name, sourcePath, line, column, 0);
        }
    }

    record DebugScope(String name, int variablesReference, boolean expensive) {
        public DebugScope { name = name == null ? "" : name; }
    }

    record DebugVariable(String name, String value, String type, int variablesReference, boolean hasChildren) {
        public DebugVariable {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
            type = type == null ? "" : type;
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
            Integer resolvedLine, String resolvedSourcePath, String condition) {
        public static final int MAX_CONDITION_LENGTH = 500;

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
            condition = normalizeCondition(condition);
        }

        public SourceBreakpoint(String sourcePath, int line, BreakpointState state, String message,
                Integer resolvedLine, String resolvedSourcePath) {
            this(sourcePath, line, state, message, resolvedLine, resolvedSourcePath, "");
        }

        public SourceBreakpoint(String sourcePath, int line) {
            this(sourcePath, line, BreakpointState.PENDING, "", null, sourcePath, "");
        }

        public SourceBreakpoint(String sourcePath, int line, BreakpointState state, String message) {
            this(sourcePath, line, state, message, null, sourcePath, "");
        }

        private static String normalizeCondition(String value) {
            String trimmed = value == null ? "" : value.strip();
            if (trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("A breakpoint condition must be one line");
            }
            if (trimmed.length() > MAX_CONDITION_LENGTH) {
                throw new IllegalArgumentException("A breakpoint condition can have at most "
                        + MAX_CONDITION_LENGTH + " characters");
            }
            return trimmed;
        }
    }

    /**
     * An expression to evaluate in one stack frame of a suspended thread. Evaluation runs code in
     * the debuggee, so providers require {@code confirmed} to be true.
     *
     * @param frameId the frame reported by {@link DebugSession#stackTrace(int)}
     * @param expression one line of Java source, at most {@link #MAX_EXPRESSION_LENGTH} characters
     * @param expectedInspectionGeneration the stop the frame was read from; a newer stop rejects the request
     * @param confirmed true only after the user explicitly approved running the expression
     */
    record EvaluationRequest(int frameId, String expression, long expectedInspectionGeneration, boolean confirmed) {
        public static final int MAX_EXPRESSION_LENGTH = 1000;

        public EvaluationRequest {
            if (frameId < 0) {
                throw new IllegalArgumentException("Frame identifiers are not negative");
            }
            expression = expression == null ? "" : expression.strip();
            if (expression.isEmpty()) {
                throw new IllegalArgumentException("An expression is required");
            }
            if (expression.indexOf('\n') >= 0 || expression.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("An expression must be one line");
            }
            if (expression.length() > MAX_EXPRESSION_LENGTH) {
                throw new IllegalArgumentException("An expression can have at most " + MAX_EXPRESSION_LENGTH
                        + " characters");
            }
        }
    }

    /** The debuggee's answer to an evaluation; {@code truncated} is set when the value was shortened. */
    record EvaluationResult(String value, String type, boolean truncated) {
        public EvaluationResult {
            value = value == null ? "" : value;
            type = type == null ? "" : type;
        }
    }

    /** Longest value a caller may assign to a variable while the debuggee is suspended. */
    int MAX_SET_VALUE_LENGTH = 1000;

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

        /** Changes whenever a new stopped event supersedes outstanding inspection requests. */
        default long inspectionGeneration() { return 0L; }

        CapabilityResult<List<DebugThread>> threads();

        default CapabilityResult<List<DebugFrame>> stackTrace(int threadId) { return unsupportedList("Stack trace"); }
        default CapabilityResult<List<DebugScope>> scopes(int frameId) { return unsupportedList("Scopes"); }
        default CapabilityResult<List<DebugVariable>> variables(int variablesReference) {
            return CapabilityResult.failed(new CapabilityFailure("Variables are not supported", ""));
        }
        default CapabilityResult<Boolean> selectFrame(DebugFrame frame) { return unsupported("Frame selection"); }
        default CapabilityResult<Boolean> selectFrame(DebugFrame frame, long expectedInspectionGeneration) {
            if (inspectionGeneration() != expectedInspectionGeneration) {
                return CapabilityResult.failed(new CapabilityFailure("The debug stop changed before frame selection", ""));
            }
            return selectFrame(frame);
        }

        /**
         * Evaluates one expression in the requested frame of the current stop. Providers reject
         * requests without confirmation, for a frame from an earlier stop, and results that arrive
         * after the debuggee resumed.
         */
        default CapabilityResult<EvaluationResult> evaluate(EvaluationRequest request) {
            return CapabilityResult.failed(new CapabilityFailure("Evaluation is not supported", ""));
        }

        /**
         * Assigns a Java expression to one variable of the current stop and returns the value and type the
         * debuggee reports afterwards. Providers reject an assignment for an earlier stop, one that the
         * adapter does not support, and a result that arrives after the debuggee resumed; a refused
         * assignment leaves the variable unchanged.
         *
         * @param variablesReference the container reported by {@link #scopes(int)} or {@link #variables(int)}
         * @param name the variable name inside that container
         * @param value one line of Java source, at most {@link #MAX_SET_VALUE_LENGTH} characters
         * @param expectedInspectionGeneration the stop the variable was read from; a newer stop rejects the request
         */
        default CapabilityResult<DebugVariable> setVariable(int variablesReference, String name, String value,
                long expectedInspectionGeneration) {
            return CapabilityResult.failed(new CapabilityFailure("Setting a value is not supported", ""));
        }

        default CapabilityResult<Boolean> resume() { return unsupported("Resume"); }
        default CapabilityResult<Boolean> pause() { return unsupported("Pause"); }
        default CapabilityResult<Boolean> stepOver() { return unsupported("Step over"); }
        default CapabilityResult<Boolean> stepInto() { return unsupported("Step into"); }
        default CapabilityResult<Boolean> stepOut() { return unsupported("Step out"); }

        private static CapabilityResult<Boolean> unsupported(String action) {
            return CapabilityResult.failed(new CapabilityFailure(action + " is not supported", ""));
        }

        private static <T> CapabilityResult<List<T>> unsupportedList(String action) {
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
