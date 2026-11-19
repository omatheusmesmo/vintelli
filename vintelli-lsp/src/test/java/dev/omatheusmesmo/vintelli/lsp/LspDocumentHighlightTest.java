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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentHighlight;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentHighlight.Kind;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.SourcePosition;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

/**
 * Document highlights through the generic client and a real fake server process: the request carries the caret
 * position, the read, write and text kinds arrive as the port records, and a server that does not advertise the
 * capability is not asked.
 */
class LspDocumentHighlightTest {

    @TempDir
    Path workspace;

    @Test
    void theRequestCarriesTheCaretPositionAndTheKindsMapToThePortRecords() throws Exception {
        Path file = Files.writeString(workspace.resolve("Demo.java"), "class Demo { }");
        Path traceFile = trace("kinds");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            List<DocumentHighlight> highlights = ok(new LspNavigationProvider(client)
                    .documentHighlights(new SourcePosition(file, 5, 8)));

            assertEquals(List.of(
                    new DocumentHighlight(new SourceRange(5, 8, 5, 12), Kind.READ),
                    new DocumentHighlight(new SourceRange(6, 0, 6, 4), Kind.WRITE),
                    new DocumentHighlight(new SourceRange(7, 2, 7, 6), Kind.TEXT),
                    new DocumentHighlight(new SourceRange(8, 1, 8, 5), Kind.TEXT)), highlights);
        }
        assertTrue(Files.readString(traceFile).contains("textDocument/documentHighlight\n"));
    }

    @Test
    void aServerThatDoesNotAdvertiseTheCapabilityIsNotAskedAndTheProviderFailsExplicitly() throws Exception {
        Path file = Files.writeString(workspace.resolve("Demo.java"), "class Demo { }");
        Path traceFile = trace("unadvertised");
        List<String> command = fakeCommand(traceFile);
        command.add("--no-document-highlight");
        try (LspClient client = new LspClient(config(command))) {
            CapabilityResult<List<DocumentHighlight>> result = new LspNavigationProvider(client)
                    .documentHighlights(new SourcePosition(file, 0, 0));

            assertTrue(result.isFailure(), result.toString());
            assertEquals("not-advertised", result.failureOf().orElseThrow().reason());
        }
        assertFalse(Files.readString(traceFile).contains("textDocument/documentHighlight"));
    }

    @Test
    void aProviderWithoutTheOperationReportsItAsNotSupported() {
        NavigationCapability bare = new NavigationCapability() {
            @Override
            public CapabilityResult<List<Location>> definition(SourcePosition position) {
                return CapabilityResult.ok(List.of());
            }

            @Override
            public CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration) {
                return CapabilityResult.ok(List.of());
            }

            @Override
            public CapabilityResult<List<NavigationCapability.DocumentSymbol>> documentSymbols(Path file) {
                return CapabilityResult.ok(List.of());
            }

            @Override
            public CapabilityResult<List<WorkspaceSymbol>> workspaceSymbols(String query) {
                return CapabilityResult.ok(List.of());
            }

            @Override
            public String identifier() {
                return "bare";
            }

            @Override
            public String provenance() {
                return "test";
            }

            @Override
            public ProviderHealth health() {
                return ProviderHealth.available();
            }
        };

        CapabilityResult<List<DocumentHighlight>> result = bare.documentHighlights(
                new SourcePosition(workspace.resolve("Demo.java"), 0, 0));

        assertEquals("not-supported", result.failureOf().orElseThrow().reason());
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
