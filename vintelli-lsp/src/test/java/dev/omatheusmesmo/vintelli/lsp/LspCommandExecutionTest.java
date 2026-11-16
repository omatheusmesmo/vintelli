package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CommandRef;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.UnsupportedChange;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEditSink.Decision;

/**
 * Runs a command of a code action against the fake server: the workspace edits the server asks for are
 * handed to the sink and answered with its decision, and a command that never answers, a dropped
 * connection and a command the server does not advertise each end in an explicit failure.
 */
class LspCommandExecutionTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(1500);
    private static final Path FAKE_FILE = Path.of(URI.create("file:///fake/Fake.java"));

    @TempDir
    Path workspace;

    @Test
    void aWorkspaceEditSentDuringACommandIsHandedToTheSinkAndAccepted() throws Exception {
        Path traceFile = trace("accepted");
        List<WorkspaceEdit> received = new CopyOnWriteArrayList<>();
        try (LspClient client = client(traceFile)) {
            CapabilityResult<Integer> result = client.executeCommand(command("fake.apply"), edit -> {
                received.add(edit);
                return Decision.accept();
            });

            assertEquals(1, ok(result));
        }
        assertEquals(List.of(new WorkspaceEdit(List.of(new DocumentEdit(FAKE_FILE, List.of(
                new TextEdit(0, 0, 0, 0, "import java.util.List;\n")))), List.of())), received);
        assertTraceContains(traceFile, "applyEdit true null\n");
    }

    @Test
    void aRefusedWorkspaceEditIsAnsweredWithTheReasonTheSinkGave() throws Exception {
        Path traceFile = trace("refused");
        List<WorkspaceEdit> received = new CopyOnWriteArrayList<>();
        try (LspClient client = client(traceFile)) {
            CapabilityResult<Integer> result = client.executeCommand(command("fake.reject"), edit -> {
                received.add(edit);
                return Decision.refuse("the editor cannot apply the create change yet");
            });

            assertEquals(1, ok(result));
        }
        assertEquals(List.of(new UnsupportedChange("create", "file:///fake/Missing.java")),
                received.getFirst().unsupported());
        assertTraceContains(traceFile, "applyEdit false the editor cannot apply the create change yet\n");
    }

    @Test
    void aCommandTheServerNeverAnswersFailsWithATimeoutAndHandsNoEditOver() throws Exception {
        Path traceFile = trace("timeout");
        List<WorkspaceEdit> received = new CopyOnWriteArrayList<>();
        try (LspClient client = client(traceFile)) {
            CapabilityResult<Integer> result = client.executeCommand(command("fake.hang"), edit -> {
                received.add(edit);
                return Decision.accept();
            });

            CapabilityFailure failure = failure(result);
            assertEquals("timeout", failure.reason());
            assertTrue(failure.detail().contains("'fake.hang'"), failure.detail());
            assertTrue(failure.detail().contains("1500 ms"), failure.detail());
        }
        assertTrue(received.isEmpty());
        assertTraceContains(traceFile, "executeCommand fake.hang\n");
    }

    @Test
    void aDroppedConnectionFailsTheCommandAndTheNextRequestStartsTheServerAgain() throws Exception {
        Path traceFile = trace("dropped");
        try (LspClient client = client(traceFile)) {
            CapabilityFailure failure = failure(client.executeCommand(command("fake.drop"), edit -> Decision.accept()));

            assertEquals("dropped", failure.reason());
            assertTrue(failure.detail().contains("'fake.drop'"), failure.detail());
            assertFalse(client.health().isAvailable(), "the dead session is not reported as running");

            assertEquals(1, ok(client.executeCommand(command("fake.apply"), edit -> Decision.accept())));
        }
        assertEquals(2, Files.readString(traceFile).split("initialize\n", -1).length - 1,
                "the server started again after the drop");
    }

    @Test
    void anEditAnsweredBeforeTheConnectionDropsIsHandedOverAndTheCommandStillFails() throws Exception {
        Path traceFile = trace("drop-after-edit");
        List<WorkspaceEdit> received = new CopyOnWriteArrayList<>();
        try (LspClient client = client(traceFile)) {
            CapabilityResult<Integer> result = client.executeCommand(command("fake.drop-after-edit"), edit -> {
                received.add(edit);
                return Decision.accept();
            });

            assertEquals("dropped", failure(result).reason());
        }
        assertEquals(1, received.size());
        assertTraceContains(traceFile, "applyEdit true null\n", "drop\n");
    }

    @Test
    void aCommandTheServerDoesNotAdvertiseIsRefusedBeforeItIsSent() throws Exception {
        Path traceFile = trace("not-advertised");
        try (LspClient client = client(traceFile)) {
            CapabilityFailure failure = failure(client.executeCommand(command("fake.unknown"),
                    edit -> Decision.accept()));

            assertEquals("not-advertised", failure.reason());
            assertFalse(Files.readString(traceFile).contains("executeCommand fake.unknown"));
        }
    }

    private LspClient client(Path traceFile) throws IOException {
        return new LspClient(config(traceFile), COMMAND_TIMEOUT);
    }

    private LspProfileConfig config(Path traceFile) throws IOException {
        LspProfileConfig.Profile profile = new StaticProfileConfig.StaticProfile(fakeCommand(traceFile),
                Optional.of(workspace.toString()), Optional.empty());
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of("unit", profile));
    }

    private static CommandRef command(String identifier) {
        return new CommandRef(identifier, identifier);
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
                    traceFile.toAbsolutePath().toString(), "--marker", "command-marker");
        } catch (URISyntaxException failure) {
            throw new IOException("cannot locate the fake server classpath", failure);
        }
    }

    private static <V> V ok(CapabilityResult<V> result) {
        return ((CapabilityResult.Ok<V>) result).value();
    }

    private static CapabilityFailure failure(CapabilityResult<?> result) {
        return result.failureOf().orElseThrow(() -> new AssertionError("expected a failed result: " + result));
    }

    private static void assertTraceContains(Path traceFile, String... methods) throws IOException {
        String text = Files.readString(traceFile);
        for (String method : methods) {
            assertTrue(text.contains(method), "the trace misses " + method.strip() + ": " + text);
        }
    }
}
