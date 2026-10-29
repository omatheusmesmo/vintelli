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
 * Stands in for the builtin text area editor in the CDI selection tests. The
 * identifier matches the documented builtin adapter so the unconfigured
 * default ranks it first.
 */
@Singleton
public final class BuiltinStubEditorProvider implements EditorBridge {

    private volatile Opened opened;

    @Override
    public String identifier() {
        return "builtin";
    }

    @Override
    public ProviderHealth health() {
        return ProviderHealth.available();
    }

    @Override
    public String provenance() {
        return "stub builtin text area";
    }

    @Override
    public CapabilityResult<Opened> open(OpenRequest request) {
        opened = new Opened(request.file(), request.line());
        return CapabilityResult.ok(opened);
    }

    @Override
    public Optional<Path> currentFile() {
        return opened == null ? Optional.empty() : Optional.of(opened.file());
    }
}
