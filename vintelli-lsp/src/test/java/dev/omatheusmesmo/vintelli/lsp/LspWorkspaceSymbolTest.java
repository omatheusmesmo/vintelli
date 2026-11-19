package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;

/**
 * Workspace symbols through the generic client and a real fake server process: the query reaches the server, the
 * kind and the container of each answered symbol arrive, a library type has no file to open, and a server that
 * cannot start fails the request explicitly.
 */
class LspWorkspaceSymbolTest {

    @TempDir
    Path workspace;

    @Test
    void aQueryListsTheAnsweredSymbolsWithTheirKindContainerAndPosition() throws Exception {
        Path traceFile = trace("answered");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            List<WorkspaceSymbolEntry> symbols = ok(client.workspaceSymbols(FakeLspServer.WORKSPACE_SYMBOL_QUERY));

            assertEquals(3, symbols.size(), symbols.toString());
            WorkspaceSymbolEntry type = symbols.getFirst();
            assertEquals("AbstractController", type.name());
            assertEquals(SymbolKind.Class.getValue(), type.kind());
            assertEquals("com.example.web", type.container());
            assertEquals(Optional.of(Path.of("/fake/workspace/AbstractController.java")), type.file());
            assertEquals(4, type.line());
            assertEquals(13, type.column());
            assertEquals(SymbolKind.Method.getValue(), symbols.get(1).kind());
            assertEquals("AbstractController", symbols.get(1).container());
        }
        assertTrue(Files.readString(traceFile).contains("workspace/symbol\n"));
    }

    @Test
    void aLibraryTypeHasNoFileSoItCannotBeOpened() throws Exception {
        try (LspClient client = new LspClient(config(fakeCommand(trace("library"))))) {
            WorkspaceSymbolEntry library = ok(client.workspaceSymbols(FakeLspServer.WORKSPACE_SYMBOL_QUERY)).get(2);

            assertEquals("ArrayList", library.name());
            assertEquals("java.util", library.container());
            assertEquals(Optional.empty(), library.file());
        }
    }

    @Test
    void aQueryTheServerHasNoSymbolsForGivesAnEmptyList() throws Exception {
        try (LspClient client = new LspClient(config(fakeCommand(trace("empty"))))) {
            assertTrue(ok(client.workspaceSymbols("Nothing")).isEmpty());
        }
    }

    @Test
    void theProviderMapsTheSymbolsToThePortRecords() throws Exception {
        try (LspClient client = new LspClient(config(fakeCommand(trace("provider"))))) {
            List<NavigationCapability.WorkspaceSymbol> symbols = ok(
                    new LspNavigationProvider(client).workspaceSymbols(FakeLspServer.WORKSPACE_SYMBOL_QUERY));

            assertEquals(new NavigationCapability.WorkspaceSymbol("AbstractController", SymbolKind.Class.getValue(),
                    "com.example.web", Optional.of(new NavigationCapability.Location(
                            Path.of("/fake/workspace/AbstractController.java"), 4, 13))), symbols.getFirst());
            assertFalse(symbols.get(2).location().isPresent());
        }
    }

    @Test
    void aServerThatCannotStartFailsTheQueryExplicitly() {
        Path missing = workspace.resolve("no-such-language-server");
        try (LspClient client = new LspClient(config(List.of(missing.toString())))) {
            CapabilityResult<List<WorkspaceSymbolEntry>> result = client.workspaceSymbols("AbCo");

            assertTrue(result.isFailure(), result.toString());
            assertFalse(result.failureOf().orElseThrow().reason().isBlank());
        }
    }

    private Path trace(String name) throws IOException {
        Path trace = workspace.resolve(name + ".trace");
        Files.writeString(trace, "");
        return trace;
    }

    private List<String> fakeCommand(Path traceFile) throws IOException {
        try {
            List<String> command = new ArrayList<>();
            command.add(ProcessHandle.current().info().command().orElse("java"));
            command.add("-cp");
            command.add(FakeLspServer.classpath());
            command.add(FakeLspServer.class.getName());
            command.add("--trace");
            command.add(traceFile.toAbsolutePath().toString());
            return command;
        } catch (URISyntaxException failure) {
            throw new IOException("cannot locate the fake server classpath", failure);
        }
    }

    private LspProfileConfig config(List<String> command) {
        LspProfileConfig.Profile profile = new StaticProfileConfig.StaticProfile(command,
                Optional.of(workspace.toString()), Optional.empty());
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of("unit", profile));
    }

    private static <V> V ok(CapabilityResult<V> result) {
        return ((CapabilityResult.Ok<V>) result).value();
    }
}
