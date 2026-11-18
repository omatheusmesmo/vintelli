package dev.omatheusmesmo.vintelli.lsp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.lsp4j.CompletionItem;

/**
 * Provider of the {@link CompletionCapability} port backed by the generic LSP client through
 * {@code textDocument/completion}. The identifier follows the active profile, so the registry names the server
 * that offers the suggestions. The LSP items that need {@code completionItem/resolve} stay here, behind the
 * tokens of the latest answer: the port only carries the tokens.
 */
@Singleton
public final class LspCompletionProvider implements CompletionCapability {

    private final LspClient client;
    private final AtomicLong tokens = new AtomicLong();
    private volatile Map<String, CompletionItem> resolvable = Map.of();

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

    /**
     * The tokens of an answer replace the tokens of the previous one, so a suggestion of an older answer that
     * is resolved later fails as stale instead of resolving another item.
     */
    @Override
    public CapabilityResult<List<Suggestion>> complete(Path file, String content, int line, int column) {
        CapabilityResult<List<CompletionEntry>> entries = client.completion(file, content, line, column);
        if (entries instanceof CapabilityResult.Ok<List<CompletionEntry>> ok) {
            Map<String, CompletionItem> issued = new HashMap<>();
            List<Suggestion> suggestions = ok.value().stream().map(entry -> suggestionOf(entry, issued)).toList();
            resolvable = Map.copyOf(issued);
            return CapabilityResult.ok(suggestions);
        }
        return CapabilityResult.failed(entries.failureOf().orElseThrow());
    }

    @Override
    public CapabilityResult<List<CompletionCapability.Replacement>> resolve(String token, Duration timeout) {
        CompletionItem item = token == null ? null : resolvable.get(token);
        if (item == null) {
            return CapabilityResult.failed(new CapabilityFailure("stale",
                    "the suggestion is no longer current; ask for the completions again"));
        }
        return client.resolveCompletion(item, timeout);
    }

    private Suggestion suggestionOf(CompletionEntry entry, Map<String, CompletionItem> issued) {
        String token = null;
        if (entry.resolveItem() != null) {
            token = "resolve-" + tokens.incrementAndGet();
            issued.put(token, entry.resolveItem());
        }
        return new Suggestion(entry.label(), entry.kind(), entry.detail(), entry.textEdit(), entry.insertText(),
                entry.snippet(), entry.filterText(), entry.sortText(), entry.additionalEdits(), token);
    }
}
