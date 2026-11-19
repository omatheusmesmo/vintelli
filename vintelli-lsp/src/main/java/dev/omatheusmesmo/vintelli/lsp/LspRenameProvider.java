package dev.omatheusmesmo.vintelli.lsp;

import java.nio.file.Path;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RenameCapability;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Provider of the {@link RenameCapability} port backed by the generic LSP client through
 * {@code textDocument/prepareRename} and {@code textDocument/rename}. The identifier follows the active profile, so
 * the registry names the server that renames the symbol.
 */
@Singleton
public final class LspRenameProvider implements RenameCapability {

    private final LspClient client;

    @Inject
    LspRenameProvider(LspClient client) {
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
        return client.activeProvenance();
    }

    @Override
    public CapabilityResult<String> prepareRename(Path file, String content, int line, int column) {
        return client.prepareRename(file, content, line, column);
    }

    @Override
    public CapabilityResult<WorkspaceEdit> rename(Path file, String content, int line, int column, String newName) {
        return client.rename(file, content, line, column, newName);
    }
}
