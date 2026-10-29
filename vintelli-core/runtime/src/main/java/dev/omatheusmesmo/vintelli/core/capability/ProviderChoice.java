package dev.omatheusmesmo.vintelli.core.capability;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of ranking one capability's providers: either the selected healthy
 * provider snapshot plus the higher ranked providers skipped with their
 * reasons, or an explicit unavailable reason for the messages tool windows
 * show. Skipped providers are never silent.
 */
record ProviderChoice(ProviderSnapshot selected, List<ProviderSnapshot> skipped, String unavailableReason) {

    static ProviderChoice selected(ProviderSnapshot snapshot, List<ProviderSnapshot> skipped) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<ProviderSnapshot> copy = skipped == null ? List.of() : List.copyOf(skipped);
        for (ProviderSnapshot passed : copy) {
            if (passed.isAvailable()) {
                throw new IllegalArgumentException("Only unhealthy providers are skipped");
            }
        }
        return new ProviderChoice(snapshot, copy, null);
    }

    static ProviderChoice unavailable(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An unavailable choice needs a reason");
        }
        return new ProviderChoice(null, List.of(), reason);
    }

    boolean isAvailable() {
        return selected != null;
    }
}
