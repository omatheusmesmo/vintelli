package dev.omatheusmesmo.vintelli.lsp;

import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Provider of the {@link CallHierarchyCapability} port backed by the generic
 * LSP client through the {@code callHierarchy/*} requests. The identifier
 * follows the active profile; callers and callees both resolve from a
 * document position, so no bare symbol handling is needed.
 */
@Singleton
public final class LspCallHierarchyProvider implements CallHierarchyCapability {

    private final LspClient client;

    @Inject
    LspCallHierarchyProvider(LspClient client) {
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
    public CapabilityResult<List<CallSite>> callers(SourcePosition position) {
        return client.callers(position);
    }

    @Override
    public CapabilityResult<List<CallSite>> callees(SourcePosition position) {
        return client.callees(position);
    }
}
