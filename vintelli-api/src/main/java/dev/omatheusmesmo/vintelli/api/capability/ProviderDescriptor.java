package dev.omatheusmesmo.vintelli.api.capability;

import java.util.List;

import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;

/**
 * Metadata every capability provider carries: a stable identifier for
 * configuration, the current health and the provenance the status bar shows.
 */
public interface ProviderDescriptor {

    /**
     * Stable identifier, unique across the providers of one capability and the
     * identifier referenced by the provider order configuration keys.
     */
    String identifier();

    /**
     * Health state right now. When unavailable this always carries a reason.
     *
     * <p>Contract of the registry: it calls this on every selection and status
     * read, so implementations must return quickly, must not block on I O and
     * must be thread safe, keeping the call safe from a render loop. A provider
     * whose health read fails is degraded into an unavailable state with the
     * failure reason instead of breaking the registry.
     */
    ProviderHealth health();

    /**
     * Where the result of this provider comes from, for example
     * {@code jdtls over stdio} or {@code IDEA MCP server}.
     */
    String provenance();

    /**
     * Additional widgets for this provider when it is selected for a
     * capability. This method must return quickly; it may schedule a bounded
     * background refresh, but must not perform I/O on the caller's thread.
     */
    default List<StatusBarWidget> statusBarWidgets(CapabilityType capability) {
        return List.of();
    }
}
