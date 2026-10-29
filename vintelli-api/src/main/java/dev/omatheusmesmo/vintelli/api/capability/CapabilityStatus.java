package dev.omatheusmesmo.vintelli.api.capability;

import java.util.List;
import java.util.Objects;

/**
 * Status row for one capability: the read model the status bar renders later,
 * for example {@code Problems: jdtls}, or an unavailable state whose reason a
 * tool window can print as is. {@code skipped} lists the higher ranked
 * providers that were passed over because they reported unhealthy, with their
 * reasons: degraded modes are never silent.
 */
public record CapabilityStatus(CapabilityType capability, String providerIdentifier, String provenance,
        ProviderHealth health, List<SkippedProvider> skipped) {

    public static CapabilityStatus available(CapabilityType capability, String providerIdentifier, String provenance,
            List<SkippedProvider> skipped) {
        return new CapabilityStatus(capability, providerIdentifier, provenance, ProviderHealth.available(), skipped);
    }

    public static CapabilityStatus unavailable(CapabilityType capability, String reason) {
        return new CapabilityStatus(capability, null, null, ProviderHealth.unavailable(reason), List.of());
    }

    public boolean isAvailable() {
        return health().isAvailable();
    }

    public CapabilityStatus {
        Objects.requireNonNull(capability, "capability");
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        if (providerIdentifier == null) {
            if (health != null && health.isAvailable()) {
                throw new IllegalArgumentException(
                        "A status row without a provider is always unavailable: pass an unavailable health with the reason");
            }
            health = ProviderHealth.unavailable(health == null ? "unavailable" : health.reason());
            provenance = null;
        } else {
            if (providerIdentifier.isBlank()) {
                throw new IllegalArgumentException("A status row always names its provider");
            }
            Objects.requireNonNull(provenance, "provenance");
            health = ProviderHealth.available();
        }
    }
}
