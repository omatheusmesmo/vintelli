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
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.FormattingOptions;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;

/**
 * Formatting and the organize imports action through the generic client and a real fake server process: the server
 * formats the text the editor holds rather than the file on disk, a range formats only its lines, the options the
 * document uses reach the server, and a server that cannot start fails the request explicitly.
 */
class LspFormattingTest {

    private static final String MARKER = "format-marker";
    private static final String TWO_SPACES = "class Fake {\n  void run() {\n  }\n}\n";
    private static final String FOUR_SPACES = "class Fake {\n    void run() {\n    }\n}\n";
    private static final String UNUSED_IMPORT = "import java.util.List;\nimport java.util.LinkedList;\n\n"
            + "class Fake {\n    List<String> names;\n}\n";

    @TempDir
    Path workspace;

    @Test
    void theServerFormatsTheTextTheEditorHoldsWithTheOptionsOfTheRequest() throws Exception {
        Path traceFile = trace("document");
        Files.writeString(document(), FOUR_SPACES);
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            List<TextEdit> edits = ok(client.formatDocument(document(), TWO_SPACES, new FormattingOptions(4, true)));
            assertEquals(List.of(new TextEdit(1, 0, 1, 2, "    "), new TextEdit(2, 0, 2, 2, "    ")), edits,
                    "the buffer is formatted, not the file on disk, which is already formatted");

            assertEquals(List.of(), ok(client.formatDocument(document(), FOUR_SPACES, new FormattingOptions(2, false))),
                    "a server that finds nothing to change answers with no edits");
        }
        assertTraceContains(traceFile, "formatting tab=4 spaces=true\n", "formatting tab=2 spaces=false\n");
    }

    @Test
    void aRangeFormatsOnlyTheLinesItCovers() throws Exception {
        Path traceFile = trace("range");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            List<TextEdit> edits = ok(client.formatRange(document(), TWO_SPACES, new SourceRange(1, 0, 1, 9),
                    new FormattingOptions(4, true)));

            assertEquals(List.of(new TextEdit(1, 0, 1, 2, "    ")), edits, "line 2 is outside the range");
        }
        assertTraceContains(traceFile, "rangeFormatting tab=4 spaces=true\n");
    }

    @Test
    void theOrganizeImportsActionIsAskedForByItsKindAndRemovesTheUnusedImport() throws Exception {
        Path traceFile = trace("imports");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            List<CodeAction> actions = ok(client.organizeImports(document(), UNUSED_IMPORT));

            assertEquals(1, actions.size());
            assertEquals("source.organizeImports", actions.getFirst().kind());
            assertEquals(List.of(new TextEdit(1, 0, 2, 0, "")),
                    actions.getFirst().edit().documents().getFirst().edits());
        }
        assertTraceContains(traceFile, "organizeImports\n");
    }

    @Test
    void aDocumentWithNoUnusedImportOffersNoOrganizeImportsAction() throws Exception {
        Path traceFile = trace("clean-imports");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            assertEquals(List.of(), ok(client.organizeImports(document(),
                    "import java.util.List;\n\nclass Fake {\n    List<String> names;\n}\n")));
        }
    }

    @Test
    void aServerThatDoesNotAdvertiseFormattingIsRefusedBeforeAnyRequest() throws Exception {
        Path traceFile = trace("no-formatting");
        List<String> command = fakeCommand(traceFile);
        command.add("--no-formatting");
        try (LspClient client = new LspClient(config(command))) {
            CapabilityResult<List<TextEdit>> result = client.formatDocument(document(), TWO_SPACES,
                    new FormattingOptions(4, true));

            assertEquals("not-supported", result.failureOf().orElseThrow().reason());
            assertTrue(result.failureOf().orElseThrow().detail().contains("does not advertise textDocument/formatting"),
                    result.toString());
            assertFalse(Files.readString(traceFile).contains("formatting"), "no formatting request was sent");
        }
    }

    @Test
    void aServerThatCannotStartFailsTheFormatExplicitly() throws Exception {
        Path missing = workspace.resolve("no-such-language-server");
        try (LspClient client = new LspClient(config(List.of(missing.toString())))) {
            CapabilityResult<List<TextEdit>> result = client.formatDocument(document(), TWO_SPACES,
                    new FormattingOptions(4, true));

            assertTrue(result.isFailure(), result.toString());
            assertFalse(result.failureOf().orElseThrow().reason().isBlank());
        }
    }

    private Path document() {
        return workspace.resolve("Fake.java");
    }

    private Path trace(String name) throws IOException {
        Path trace = workspace.resolve(name + ".trace");
        Files.writeString(trace, "");
        return trace;
    }

    private static void assertTraceContains(Path traceFile, String... lines) throws IOException {
        String trace = Files.readString(traceFile);
        for (String line : lines) {
            assertTrue(trace.contains(line), "the trace lacks " + line.strip() + ":\n" + trace);
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
