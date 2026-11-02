package dev.omatheusmesmo.vintelli.lsp;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Provider of the {@link CodeActionCapability} port backed by the generic LSP
 * client through {@code textDocument/codeAction}. The identifier follows the
 * active profile, so the registry names the server that offers the fixes.
 */
@Singleton
public final class LspCodeActionProvider implements CodeActionCapability {

    private final LspClient client;

    @Inject
    LspCodeActionProvider(LspClient client) {
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
    public CapabilityResult<List<CodeActionCapability.CodeAction>> codeActions(Path file, SourceRange range,
            List<DiagnosticsCapability.Diagnostic> diagnostics) {
        return client.codeActions(file, range, diagnostics);
    }
}
