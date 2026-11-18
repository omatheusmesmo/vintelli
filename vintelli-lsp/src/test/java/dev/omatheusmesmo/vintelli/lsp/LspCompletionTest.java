package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Kind;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Replacement;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;

/**
 * Completion through the generic client and a real fake server process: the entries keep the server's sort
 * order and carry the text edit, snippet and filter fields, the document the editor holds is sent before the
 * request, and a server that cannot start fails the request explicitly.
 */
class LspCompletionTest {

    private static final String MARKER = "completion-marker";
    private static final int RICH_LINE = FakeLspServer.RICH_COMPLETION_LINE;
    private static final int BUFFER_LINE = FakeLspServer.BUFFER_COMPLETION_LINE;

    @TempDir
    Path workspace;

    @Test
    void entriesKeepTheServerSortOrderAndCarryTheInsertDetails() throws Exception {
        Path traceFile = trace("entries");
        Files.writeString(document(), "class Fake {}");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            List<CompletionEntry> entries = ok(client.completion(document(), RICH_LINE, 0));

            assertEquals(List.of("alpha", "beta", "gamma", "zeta"), entries.stream().map(CompletionEntry::label).toList(),
                    "the server sent zeta first and sorts by sortText");

            CompletionEntry alpha = entries.get(0);
            assertEquals(Kind.SNIPPET, alpha.kind());
            assertTrue(alpha.snippet());
            assertEquals("alpha(${1:value}) $0", alpha.insertText());
            assertEquals("(value: String)", alpha.detail());
            assertEquals("alphaFilter", alpha.filterText());
            assertEquals("0001", alpha.sortText());
            assertNull(alpha.textEdit());

            CompletionEntry beta = entries.get(1);
            assertEquals(Kind.METHOD, beta.kind());
            assertEquals(new Replacement(RICH_LINE, 0, RICH_LINE, 4, "beta()"), beta.textEdit());
            assertFalse(beta.snippet());

            CompletionEntry gamma = entries.get(2);
            assertEquals(new Replacement(RICH_LINE, 0, RICH_LINE, 2, "gamma()"), gamma.textEdit(),
                    "an insert and replace edit contributes its insert range");

            CompletionEntry zeta = entries.get(3);
            assertEquals(Kind.FIELD, zeta.kind());
            assertEquals("int", zeta.detail());
            assertNull(zeta.textEdit());
        }
        assertTraceContains(traceFile, "completion");
    }

    @Test
    void theDocumentTheEditorHoldsIsSentBeforeTheRequest() throws Exception {
        Path traceFile = trace("buffer");
        Files.writeString(document(), "class Fake {}");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            String unsaved = "class Fake { int " + FakeLspServer.UNSAVED_MARKER + "; }";

            assertEquals(List.of("buffer_unsaved"), labels(client.completion(document(), unsaved, BUFFER_LINE, 0)));
            assertEquals(List.of("buffer_disk"), labels(client.completion(document(), "class Fake {}", BUFFER_LINE, 0)),
                    "a later buffer replaces the one the server holds");
            assertEquals(List.of("buffer_disk"), labels(client.completion(document(), BUFFER_LINE, 0)),
                    "a completion without a buffer leaves the open buffer alone");
        }
        String trace = Files.readString(traceFile);
        assertTrue(trace.indexOf("didChange") < trace.lastIndexOf("completion"), trace);
    }

    @Test
    void aServerThatCannotStartFailsTheRequestExplicitly() throws Exception {
        Path missing = workspace.resolve("no-such-language-server");
        try (LspClient client = new LspClient(config(List.of(missing.toString())))) {
            CapabilityResult<List<CompletionEntry>> result = client.completion(document(), "class Fake {}", 0, 0);

            assertTrue(result.isFailure(), result.toString());
            assertFalse(result.failureOf().orElseThrow().reason().isBlank());
        }
    }

    @Test
    void theProviderExposesTheSuggestionsOfTheActiveProfile() throws Exception {
        Path traceFile = trace("provider");
        Files.writeString(document(), "class Fake {}");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            LspCompletionProvider provider = new LspCompletionProvider(client);

            assertEquals("unit", provider.identifier());
            CapabilityResult<List<Suggestion>> result = provider.complete(document(), "class Fake {}", RICH_LINE, 0);
            List<Suggestion> suggestions = ok(result);

            assertEquals(List.of("alpha", "beta", "gamma", "zeta"),
                    suggestions.stream().map(Suggestion::label).toList());
            assertEquals(Kind.SNIPPET, suggestions.get(0).kind());
            assertEquals(new Replacement(RICH_LINE, 0, RICH_LINE, 4, "beta()"), suggestions.get(1).textEdit());
        }
    }

    private List<String> labels(CapabilityResult<List<CompletionEntry>> result) {
        return ok(result).stream().map(CompletionEntry::label).toList();
    }

    private Path document() {
        return workspace.resolve("Fake.java");
    }

    private Path trace(String name) throws IOException {
        Path trace = workspace.resolve(name + ".trace");
        Files.writeString(trace, "");
        return trace;
    }

    private static void assertTraceContains(Path traceFile, String... methods) throws IOException {
        String trace = Files.readString(traceFile);
        for (String method : methods) {
            assertTrue(trace.contains(method + "\n"), "the trace lacks " + method + ":\n" + trace);
        }
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
            command.add("--marker");
            command.add(MARKER);
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
