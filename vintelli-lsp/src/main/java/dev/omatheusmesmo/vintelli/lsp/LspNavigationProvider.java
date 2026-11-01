package dev.omatheusmesmo.vintelli.lsp;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Navigation provider backed by the configured language server over stdio. */
@Singleton
public final class LspNavigationProvider implements NavigationCapability {

    private final LspClient client;

    @Inject
    LspNavigationProvider(LspClient client) {
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
    public CapabilityResult<List<Location>> definition(SourcePosition position) {
        return map(client.definition(position.filePath(), position.line(), position.column()));
    }

    @Override
    public CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration) {
        return map(client.references(position.filePath(), position.line(), position.column(), includeDeclaration));
    }

    @Override
    public CapabilityResult<List<NavigationCapability.DocumentSymbol>> documentSymbols(Path file) {
        CapabilityResult<List<dev.omatheusmesmo.vintelli.lsp.DocumentSymbol>> result = client.documentSymbols(file);
        if (result instanceof CapabilityResult.Ok<List<dev.omatheusmesmo.vintelli.lsp.DocumentSymbol>> ok) {
            return CapabilityResult.ok(ok.value().stream().map(LspNavigationProvider::map).toList());
        }
        CapabilityResult.Failed<List<dev.omatheusmesmo.vintelli.lsp.DocumentSymbol>> failed =
                (CapabilityResult.Failed<List<dev.omatheusmesmo.vintelli.lsp.DocumentSymbol>>) result;
        return CapabilityResult.failed(failed.failure());
    }

    private static NavigationCapability.DocumentSymbol map(dev.omatheusmesmo.vintelli.lsp.DocumentSymbol symbol) {
        return new NavigationCapability.DocumentSymbol(symbol.name(), symbol.kind().getValue(), symbol.startLine(), symbol.startColumn(),
                symbol.endLine(), symbol.endColumn(), symbol.selectionStartLine(), symbol.selectionStartColumn(),
                symbol.selectionEndLine(), symbol.selectionEndColumn(),
                symbol.children().stream().map(LspNavigationProvider::map).toList());
    }

    private static CapabilityResult<List<Location>> map(CapabilityResult<List<CodeReference>> result) {
        if (result instanceof CapabilityResult.Ok<List<CodeReference>> ok) {
            return CapabilityResult.ok(ok.value().stream()
                    .map(reference -> new Location(Path.of(reference.targetFile()), reference.startLine(),
                            reference.startColumn()))
                    .toList());
        }
        CapabilityResult.Failed<List<CodeReference>> failed = (CapabilityResult.Failed<List<CodeReference>>) result;
        return CapabilityResult.failed(failed.failure());
    }
}
