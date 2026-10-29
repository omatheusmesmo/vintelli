package dev.omatheusmesmo.vintelli.api.capability;

/**
 * A higher ranked provider that the registry passed over because it reported
 * unhealthy. The status model exposes these so a degraded selection is
 * visible, not silent.
 */
public record SkippedProvider(String identifier, String reason) {

    public SkippedProvider {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("A skipped provider always has an identifier");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A skipped provider always carries its reason");
        }
    }
}
