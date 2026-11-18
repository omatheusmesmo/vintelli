package dev.omatheusmesmo.vintelli.lsp;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Provider of the {@link CompletionCapability} port backed by the generic LSP client through
 * {@code textDocument/completion}. The identifier follows the active profile, so the registry names the server
 * that offers the suggestions.
 */
@Singleton
public final class LspCompletionProvider implements CompletionCapability {

    private final LspClient client;

    @Inject
    LspCompletionProvider(LspClient client) {
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
    public CapabilityResult<List<Suggestion>> complete(Path file, String content, int line, int column) {
        CapabilityResult<List<CompletionEntry>> entries = client.completion(file, content, line, column);
        if (entries instanceof CapabilityResult.Ok<List<CompletionEntry>> ok) {
            return CapabilityResult.ok(ok.value().stream().map(LspCompletionProvider::suggestionOf).toList());
        }
        return CapabilityResult.failed(entries.failureOf().orElseThrow());
    }

    private static Suggestion suggestionOf(CompletionEntry entry) {
        return new Suggestion(entry.label(), entry.kind(), entry.detail(), entry.textEdit(), entry.insertText(),
                entry.snippet(), entry.filterText(), entry.sortText());
    }
}
