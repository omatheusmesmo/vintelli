package dev.omatheusmesmo.vintelli.capabilities;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import jakarta.inject.Singleton;

/**
 * Second stand-in provider bean with the lowest rank: the registry selects it
 * when the ranked first one flips unhealthy.
 */
@Singleton
public final class BackupStubReadFileProvider implements ReadFileCapability {

    private volatile ProviderHealth health = ProviderHealth.available();

    void markUnavailable(String reason) {
        health = ProviderHealth.unavailable(reason);
    }

    @Override
    public String identifier() {
        return "backup-reader";
    }

    @Override
    public ProviderHealth health() {
        return health;
    }

    @Override
    public String provenance() {
        return "stub filesystem, ranked last";
    }

    @Override
    public CapabilityResult<String> readText(Path file) {
        return CapabilityResult.failed(new CapabilityFailure("backup stub cannot read", file.toString()));
    }

    @Override
    public CapabilityResult<List<DirEntry>> list(Path directory) {
        return CapabilityResult.failed(new CapabilityFailure("backup stub cannot list", directory.toString()));
    }
}
