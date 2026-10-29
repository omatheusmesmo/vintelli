package dev.omatheusmesmo.vintelli.capabilities;

import java.nio.file.Path;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.Opened;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.inject.Singleton;

/**
 * Stands in for a generic external editor adapter in the CDI selection tests.
 * It carries a flipping health so a live bean drives the fallback from the
 * configured adapter to the builtin one.
 */
@Singleton
public final class GenericStubEditorProvider implements EditorBridge {

    private volatile ProviderHealth health = ProviderHealth.available();

    void markUnavailable(String reason) {
        health = ProviderHealth.unavailable(reason);
    }

    @Override
    public String identifier() {
        return "generic";
    }

    @Override
    public ProviderHealth health() {
        return health;
    }

    @Override
    public String provenance() {
        return "stub editor process, ranked first by the test profile";
    }

    @Override
    public CapabilityResult<Opened> open(OpenRequest request) {
        return CapabilityResult.ok(new Opened(request.file(), request.line()));
    }

    @Override
    public Optional<Path> currentFile() {
        return Optional.empty();
    }
}
