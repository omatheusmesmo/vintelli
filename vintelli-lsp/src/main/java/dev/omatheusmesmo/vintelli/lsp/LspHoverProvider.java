package dev.omatheusmesmo.vintelli.lsp;

import java.nio.file.Path;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Provider of the {@link HoverCapability} port backed by the generic LSP client through
 * {@code textDocument/hover}. The identifier follows the active profile, so the registry names the server that
 * answers the documentation.
 */
@Singleton
public final class LspHoverProvider implements HoverCapability {

    private final LspClient client;

    @Inject
    LspHoverProvider(LspClient client) {
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
    public CapabilityResult<Documentation> hover(Path file, String content, int line, int column) {
        CapabilityResult<String> markdown = client.hover(file, content, line, column);
        if (markdown instanceof CapabilityResult.Ok<String> ok) {
            return CapabilityResult.ok(new Documentation(ok.value()));
        }
        return CapabilityResult.failed(markdown.failureOf().orElseThrow());
    }
}
