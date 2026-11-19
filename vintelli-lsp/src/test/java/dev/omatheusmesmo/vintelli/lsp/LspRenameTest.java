package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;

/**
 * Rename through the generic client and a real fake server process: the server's suggestion for the symbol
 * arrives, the declaration and the call in another file are both changed, a rename that also renames a file
 * carries that change as unsupported, a position the server rejects is not renameable, and a session without a
 * server says why.
 */
class LspRenameTest {

    private static final String MARKER = "rename-marker";
    private static final String CONTENT = "class Fake {\n\n    void greet() {}\n}\n";
    private static final int CARET_COLUMN = 5;

    @TempDir
    Path workspace;

    @Test
    void theServerSuggestsTheNameOfTheSymbolAtTheCaret() throws Exception {
        Path traceFile = trace("suggest");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            assertEquals("greet", ok(client.prepareRename(document(), CONTENT, FakeLspServer.RENAME_LINE, CARET_COLUMN)));
        }
        assertTraceContains(traceFile, "prepareRename");
    }

    @Test
    void aRenameChangesTheDeclarationAndTheCallInAnotherFile() throws Exception {
        Path traceFile = trace("rename");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            WorkspaceEdit edit = ok(client.rename(document(), CONTENT, FakeLspServer.RENAME_LINE, CARET_COLUMN,
                    "salute"));

            assertEquals(2, edit.documents().size());
            assertEquals(document().toRealPath(), edit.documents().get(0).file());
            assertEquals(List.of(new TextEdit(FakeLspServer.RENAME_LINE, 4, FakeLspServer.RENAME_LINE, 9, "salute")),
                    edit.documents().get(0).edits());
            assertEquals(workspace.toRealPath().resolve(FakeLspServer.CALLER_FILE), edit.documents().get(1).file());
            assertEquals(List.of(new TextEdit(0, 8, 0, 13, "salute")), edit.documents().get(1).edits());
            assertTrue(edit.unsupported().isEmpty(), edit.unsupported().toString());
        }
        assertTraceContains(traceFile, "rename");
    }

    @Test
    void aRenameThatAlsoRenamesAFileCarriesTheFileChangeAsUnsupported() throws Exception {
        Path traceFile = trace("file-rename");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            WorkspaceEdit edit = ok(client.rename(document(), CONTENT, FakeLspServer.RENAME_LINE, CARET_COLUMN,
                    FakeLspServer.RENAMED_FILE));

            assertEquals(1, edit.unsupported().size(), edit.unsupported().toString());
            assertEquals("rename", edit.unsupported().getFirst().kind());
        }
    }

    @Test
    void aPositionTheServerRejectsIsNotRenameable() throws Exception {
        Path traceFile = trace("rejected");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            CapabilityResult<String> result = client.prepareRename(document(), CONTENT,
                    FakeLspServer.NOT_RENAMEABLE_LINE, 0);

            assertEquals("not-resolvable", failure(result).reason());
        }
    }

    @Test
    void aSessionWithoutAServerSaysWhyTheRenameCannotRun() throws Exception {
        LspProfileConfig noProfiles = new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of());
        LanguageServerDiscovery disabled = new LanguageServerDiscovery(false, Map.of(), "linux", "",
                workspace);
        try (LspClient client = new LspClient(noProfiles, disabled)) {
            CapabilityResult<String> result = client.prepareRename(document(), CONTENT, FakeLspServer.RENAME_LINE,
                    CARET_COLUMN);

            CapabilityFailure failure = failure(result);
            assertEquals("unavailable", failure.reason());
            assertTrue(failure.detail().contains(LanguageServerDiscovery.NO_SERVER_REASON), failure.detail());
        }
    }

    private Path document() throws IOException {
        Path document = workspace.resolve("Fake.java");
        Files.writeString(document, CONTENT);
        return document;
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

    private static CapabilityFailure failure(CapabilityResult<?> result) {
        return result.failureOf().orElseThrow();
    }
}
