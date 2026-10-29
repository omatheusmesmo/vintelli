package dev.omatheusmesmo.vintelli.lsp;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Provider of the {@link DiagnosticsCapability} port backed by the generic LSP
 * client. The identifier follows the active profile, so the ranking in the
 * registry names the server that actually answers, for example `Problems:
 * jdtls`.
 */
@Singleton
public final class LspDiagnosticsProvider implements DiagnosticsCapability {

    private final LspClient client;

    @Inject
    LspDiagnosticsProvider(LspClient client) {
        this.client = client;
    }

    @Override
    public ProviderHealth health() {
        return client.health();
    }

    @Override
    public String identifier() {
        return client.activeProfileName().orElse("lsp");
    }

    @Override
    public String provenance() {
        return client.activeProfileName().orElse("lsp") + " over stdio";
    }

    @Override
    public CapabilityResult<List<DiagnosticsCapability.Diagnostic>> diagnostics(Path file) {
        return client.diagnostics(file);
    }
}
