package dev.omatheusmesmo.vintelli.core.capability;

import dev.omatheusmesmo.vintelli.api.capability.ProviderDescriptor;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

/**
 * Immutable copy of a provider's metadata, taken by the registry when it ranks
 * the providers of a capability. The copy keeps the read model stable while
 * providers keep reporting live health. The identifier input is the value the
 * registry validated once at registration, so snapshot and lookup always agree.
 * A provider that throws or returns a null value while its provenance or health
 * is read is degraded here, with the failure as reason, instead of breaking the
 * whole read.
 */
record ProviderSnapshot(String identifier, String provenance, ProviderHealth health) {

    static ProviderSnapshot of(String identifier, ProviderDescriptor port) {
        try {
            return new ProviderSnapshot(identifier, port.provenance(), port.health());
        } catch (RuntimeException failure) {
            return new ProviderSnapshot(identifier, "unknown provenance",
                    ProviderHealth.unavailable("health check failed: " + failure));
        }
    }

    boolean isAvailable() {
        return health().isAvailable();
    }

    ProviderSnapshot {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("A provider snapshot always names a non blank identifier");
        }
        provenance = provenance == null || provenance.isBlank() ? "unknown provenance" : provenance;
        if (health == null) {
            health = ProviderHealth.unavailable("health check returned nothing");
        }
    }
}
