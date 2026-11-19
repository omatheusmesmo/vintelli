package dev.omatheusmesmo.vintelli.tui.symbol;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.WorkspaceSymbol;

final class SymbolFixtures {

    static final Duration WAIT = Duration.ofSeconds(5);

    private SymbolFixtures() {
    }

    static WorkspaceSymbol type(String name, String container) {
        return new WorkspaceSymbol(name, SymbolKinds.CLASS, container,
                Optional.of(new Location(Path.of(name + ".java"), 3, 2)));
    }

    static WorkspaceSymbol symbol(String name, int kind, String container) {
        return new WorkspaceSymbol(name, kind, container, Optional.of(new Location(Path.of(name + ".java"), 3, 2)));
    }

    static WorkspaceSymbol library(String name, String container) {
        return new WorkspaceSymbol(name, SymbolKinds.CLASS, container, Optional.empty());
    }

    static void awaitSymbols(SymbolSearchModel model) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (model.view().symbols().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(!model.view().symbols().isEmpty(), "the symbols did not arrive in time");
    }

    static List<String> names(SymbolSearchModel model) {
        return model.view().symbols().stream().map(WorkspaceSymbol::name).toList();
    }
}
