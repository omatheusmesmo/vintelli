package dev.omatheusmesmo.vintelli.capabilities;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import jakarta.inject.Singleton;

/**
 * Stands in for a real discovered provider bean in the CDI selection tests.
 * Two of these exist, so the ranked order is exercised end to end through
 * bean discovery and the health flip happens on a live bean.
 */
@Singleton
public final class PrimaryStubReadFileProvider implements ReadFileCapability {

    private volatile ProviderHealth health = ProviderHealth.available();

    void markUnavailable(String reason) {
        health = ProviderHealth.unavailable(reason);
    }

    @Override
    public String identifier() {
        return "primary-reader";
    }

    @Override
    public ProviderHealth health() {
        return health;
    }

    @Override
    public String provenance() {
        return "stub filesystem, ranked first";
    }

    @Override
    public CapabilityResult<String> readText(Path file) {
        return CapabilityResult.failed(new CapabilityFailure("primary stub cannot read", file.toString()));
    }

    @Override
    public CapabilityResult<List<DirEntry>> list(Path directory) {
        return CapabilityResult.failed(new CapabilityFailure("primary stub cannot list", directory.toString()));
    }
}
