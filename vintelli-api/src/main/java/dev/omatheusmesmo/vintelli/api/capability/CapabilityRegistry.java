package dev.omatheusmesmo.vintelli.api.capability;

import java.util.List;

/**
 * Read only view of the capability selection that the app runs with. Tool
 * windows, the status bar and the CLI consume this interface only; the module
 * that wires real providers owns the implementation.
 *
 * <p>Every read recomputes the selection from live provider health, so a
 * provider flipping unhealthy is reflected on the next call without any
 * registration step. The snapshot reads each capability separately: it is not
 * an atomic view across capabilities.
 */
public interface CapabilityRegistry {

    /**
     * @return the current run provider, or an explicit unavailable state
     */
    CapabilitySelection<RunCapability> run();

    /**
     * @return the current terminal provider, or an explicit unavailable state
     */
    CapabilitySelection<TerminalCapability> terminal();

    /**
     * @return the current read-file provider, or an explicit unavailable state
     */
    CapabilitySelection<ReadFileCapability> readFile();

    /**
     * @return the current diagnostics provider, or an explicit unavailable state
     */
    CapabilitySelection<DiagnosticsCapability> diagnostics();

    /**
     * @return the current call-hierarchy provider, or an explicit unavailable state
     */
    CapabilitySelection<CallHierarchyCapability> callHierarchy();

    /**
     * @return the current editor bridging provider, or an explicit unavailable state
     */
    CapabilitySelection<EditorBridge> editorBridge();

    /**
     * One status row per capability: the read model a status bar renders (for
     * example the provider identifier of {@link CapabilityType#DIAGNOSTICS} as
     * {@code Problems: jdtls}).
     */
    List<CapabilityStatus> snapshot();
}
