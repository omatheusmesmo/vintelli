package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;

/**
 * Generate asks the server for the {@code source} kind through {@code context.only}. The fake server answers
 * the generate actions of a class: one with a plain edit, one jdtls prompt command and an organize imports
 * action that the client filters out, and an empty list where there is nothing to generate.
 */
class LspGenerateTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path workspace;

    @Test
    void theSourceKindIsSentAndTheGenerateActionsComeBackWithTheirEditOrPromptCommand() throws Exception {
        Path traceFile = trace("generate");
        Path source = source();
        try (LspClient client = client(traceFile)) {
            List<CodeActionCapability.CodeAction> actions = ok(client.codeActions(source,
                    new SourceRange(0, 0, 0, 0), List.of(), List.of("source")));

            assertEquals(List.of("Generate Getters and Setters", "Generate toString()", "Organize Imports"),
                    actions.stream().map(CodeActionCapability.CodeAction::title).toList());
            CodeActionCapability.CodeAction accessors = actions.getFirst();
            assertEquals("source.generate.accessors", accessors.kind());
            assertEquals(1, accessors.edit().documents().getFirst().edits().size());
            assertNull(accessors.command());
            CodeActionCapability.CodeAction toString = actions.get(1);
            assertNull(toString.edit());
            assertEquals("java.action.generateToStringPrompt", toString.command().identifier());
        }
        assertTrue(Files.readString(traceFile).contains("codeAction only [source]\n"),
                "the server was asked for the source kind only");
    }

    @Test
    void aClassWithNothingToGenerateAnswersAnEmptyList() throws Exception {
        Path source = source();
        try (LspClient client = client(trace("nothing"))) {
            assertEquals(List.of(), ok(client.codeActions(source, new SourceRange(1, 0, 1, 0), List.of(),
                    List.of("source"))));
        }
    }

    private LspClient client(Path traceFile) throws IOException {
        return new LspClient(config(traceFile), REQUEST_TIMEOUT);
    }

    private LspProfileConfig config(Path traceFile) throws IOException {
        LspProfileConfig.Profile profile = new StaticProfileConfig.StaticProfile(fakeCommand(traceFile),
                Optional.of(workspace.toString()), Optional.empty());
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of("unit", profile));
    }

    private Path source() throws IOException {
        return Files.writeString(workspace.resolve("Greeter.java"), "class Greeter {\n    int count;\n}\n");
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
                    traceFile.toAbsolutePath().toString(), "--marker", "generate-marker");
        } catch (URISyntaxException failure) {
            throw new IOException("cannot locate the fake server classpath", failure);
        }
    }

    private static <V> V ok(CapabilityResult<V> result) {
        return ((CapabilityResult.Ok<V>) result).value();
    }
}
