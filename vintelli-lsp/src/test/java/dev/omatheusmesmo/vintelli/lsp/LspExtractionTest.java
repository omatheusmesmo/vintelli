package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;

/**
 * The extract refactorings reach the language server as {@code textDocument/codeAction} requests that name their
 * kinds in {@code context.only}. The variable extraction of jdt.ls carries its edit in the argument of the client
 * side command {@code java.apply.workspaceEdit}, which the client applies itself and never sends back.
 */
class LspExtractionTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(1500);
    private static final List<String> VARIABLE = List.of("refactor.extract.variable");
    private static final List<String> METHOD = List.of("refactor.extract.function");

    @TempDir
    Path workspace;

    @Test
    void theVariableExtractionArrivesAsAnEditOfItsKindAndTheKindIsAskedFor() throws Exception {
        Path source = source();
        Path traceFile = trace("variable");
        try (LspClient client = client(traceFile)) {
            List<CodeAction> actions = ok(client.codeActions(source, new SourceRange(5, 0, 5, 0), List.of(), VARIABLE));

            assertEquals(1, actions.size());
            CodeAction extraction = actions.getFirst();
            assertEquals("Extract to local variable", extraction.title());
            assertEquals("refactor.extract.variable", extraction.kind());
            assertNull(extraction.command(), "the client command is applied as an edit, never sent to the server");
            assertEquals(List.of(new DocumentEdit(source.toRealPath(), List.of(
                    new TextEdit(0, 0, 0, 0, "int extracted = 1;\n")))), extraction.edit().documents());
        }
        assertTrue(Files.readString(traceFile).contains("codeAction only [refactor.extract.variable]\n"));
        assertFalse(Files.readString(traceFile).contains("executeCommand java.apply.workspaceEdit"));
    }

    @Test
    void theMethodExtractionKeepsItsOwnEditAndKind() throws Exception {
        Path source = source();
        try (LspClient client = client(trace("method"))) {
            List<CodeAction> actions = ok(client.codeActions(source, new SourceRange(6, 0, 6, 0), List.of(), METHOD));

            assertEquals(1, actions.size());
            assertEquals("refactor.extract.function", actions.getFirst().kind());
            assertNull(actions.getFirst().command());
            assertEquals(List.of(new DocumentEdit(source.toRealPath(), List.of(
                    new TextEdit(0, 0, 0, 0, "void extracted() {}\n")))), actions.getFirst().edit().documents());
        }
    }

    @Test
    void aRangeWithNothingToExtractAnswersNoActions() throws Exception {
        Path source = source();
        try (LspClient client = client(trace("nothing"))) {
            assertEquals(List.of(), ok(client.codeActions(source, new SourceRange(1, 0, 1, 0), List.of(), VARIABLE)));
        }
    }

    private Path source() throws IOException {
        Path source = workspace.resolve("Demo.java");
        Files.writeString(source, "class Demo {\n}\n");
        return source;
    }

    private LspClient client(Path traceFile) throws IOException {
        return new LspClient(config(traceFile), COMMAND_TIMEOUT);
    }

    private LspProfileConfig config(Path traceFile) throws IOException {
        LspProfileConfig.Profile profile = new StaticProfileConfig.StaticProfile(fakeCommand(traceFile),
                Optional.of(workspace.toString()), Optional.empty());
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of("unit", profile));
    }

    private Path trace(String name) throws IOException {
        Path trace = workspace.resolve(name + ".trace");
        Files.writeString(trace, "");
        return trace;
    }

    private static List<String> fakeCommand(Path traceFile) throws IOException {
        try {
            return List.of(ProcessHandle.current().info().command().orElse("java"), "-cp",
                    FakeLspServer.classpath(), FakeLspServer.class.getName(), "--trace",
                    traceFile.toAbsolutePath().toString(), "--marker", "extraction-marker");
        } catch (URISyntaxException failure) {
            throw new IOException("cannot locate the fake server classpath", failure);
        }
    }

    private static <V> V ok(CapabilityResult<V> result) {
        return ((CapabilityResult.Ok<V>) result).value();
    }
}
