package dev.omatheusmesmo.vintelli.api.capability;

import java.util.List;
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

    enum State {
        STARTING,
        ATTACHING,
        STOPPED,
        RUNNING,
        TERMINATED,
        DISCONNECTED,
        FAILED
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
            List<DebugThread> threads, String reason, String stoppedReason) {

        public DebugSessionSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(configurationName, "configurationName");
            Objects.requireNonNull(state, "state");
            threads = threads == null ? List.of() : List.copyOf(threads);
            reason = reason == null ? "" : reason;
            stoppedReason = stoppedReason == null ? "" : stoppedReason;
        }
    }

    interface DebugSession {

        DebugSessionSnapshot snapshot();

        CapabilityResult<List<DebugThread>> threads();

        /**
         * Detaches from the adapter. A launch-owned run is cancelled as part of
         * cleanup; an externally started debuggee remains running.
         */
        CapabilityResult<Boolean> disconnect();

        /** Requests the same ownership-aware cleanup as {@link #disconnect()}. */
        void cancel();
    }
}
