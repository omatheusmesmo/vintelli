package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CommandRef;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.QuickFixController;
import dev.omatheusmesmo.vintelli.tui.editor.QuickFixRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A command action of the quick fix popup, driven headlessly: the server's workspace edit lands as one
 * undoable step when the command completes, and a command that times out or loses its connection leaves the
 * document as it was and says so on the status line.
 */
class QuickFixCommandTest {

    private static final String TEXT = "one\ntwo\nthree\nfour\n";
    private static final String TITLE = "Run the fixer";
    private static final String UNCHANGED = "; nothing was applied, the document is unchanged";

    @TempDir
    Path project;

    @Test
    void aCommandEditAppliesAsOneUndoStepAndTheStatusNamesIt() throws Exception {
        Path file = write("Applied.txt", TEXT);
        Session session = new Session(file, (command, edits) -> {
            edits.accept(lineEdit(file, "THREE"));
            return CapabilityResult.ok(1);
        });

        assertTrue(session.runAction().startsWith("Running " + TITLE), "the popup says the command is running");
        session.awaitOutcome();

        assertEquals("Quick fix applied: " + TITLE + " (1 edit)", session.status.get());
        assertEquals("one\ntwo\nTHREE\nfour\n", session.editor.activeDocument().orElseThrow().text());
        assertTrue(session.editor.activeDocument().orElseThrow().dirty());
        assertTrue(session.editor.undo());
        assertEquals(TEXT, session.editor.activeDocument().orElseThrow().text());
        assertFalse(session.editor.undo(), "the command edit is a single undo step");
    }

    @Test
    void aCommandThatTimesOutLeavesTheDocumentUnchangedAndSaysSo() throws Exception {
        Path file = write("Timeout.txt", TEXT);
        Session session = new Session(file, (command, edits) -> CapabilityResult.failed(new CapabilityFailure(
                "timeout", "the language server of profile 'jdtls' did not answer 'fixture.fix' within 60000 ms")));

        session.runAction();
        session.awaitOutcome();

        assertTrue(session.status.get().startsWith("Quick fix '" + TITLE + "' did not complete: "),
                session.status.get());
        assertTrue(session.status.get().contains("did not answer 'fixture.fix' within 60000 ms"),
                session.status.get());
        assertTrue(session.status.get().endsWith(UNCHANGED), session.status.get());
        assertEquals(TEXT, session.editor.activeDocument().orElseThrow().text());
        assertFalse(session.editor.activeDocument().orElseThrow().dirty());
    }

    @Test
    void aDroppedConnectionAfterAnEditLeavesTheDocumentUnchangedAndSaysSo() throws Exception {
        Path file = write("Dropped.txt", TEXT);
        Session session = new Session(file, (command, edits) -> {
            edits.accept(lineEdit(file, "THREE"));
            return CapabilityResult.failed(new CapabilityFailure("dropped",
                    "the connection to the language server of profile 'jdtls' dropped while it ran 'fixture.fix'"));
        });

        session.runAction();
        session.awaitOutcome();

        assertTrue(session.status.get().contains("dropped while it ran 'fixture.fix'"), session.status.get());
        assertTrue(session.status.get().endsWith(UNCHANGED), session.status.get());
        assertEquals(TEXT, session.editor.activeDocument().orElseThrow().text());
        assertFalse(session.editor.activeDocument().orElseThrow().dirty());
    }

    private static WorkspaceEdit lineEdit(Path file, String text) {
        return new WorkspaceEdit(List.of(new DocumentEdit(file, List.of(new TextEdit(2, 0, 2, 5, text)))), List.of());
    }

    private Path write(String name, String contents) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, contents);
        return file.toRealPath();
    }

    /**
     * An editor with one open file, a popup controller that delivers its updates to a queue the test drains,
     * and a provider whose only action carries a command.
     */
    private final class Session {

        private final BuiltinEditor editor;
        private final QuickFixController controller;
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        private final AtomicReference<String> status = new AtomicReference<>("");

        Session(Path file, QuickFixFixtures.CommandHandler commands) {
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, 2));
            CodeAction action = new CodeAction(TITLE, "quickfix", false, null, new CommandRef("fixture.fix", TITLE));
            controller = new QuickFixController(
                    QuickFixFixtures.registryWith(QuickFixFixtures.answering(path -> CapabilityResult.ok(List.of(action)),
                            commands)),
                    editor, deliveries::add, status::set);
        }

        String runAction() throws InterruptedException {
            QuickFixRequest request = editor.quickFixRequest().orElseThrow();
            controller.open(request);
            next().run();
            return controller.press(OverlayKey.ENTER);
        }

        void awaitOutcome() throws InterruptedException {
            next().run();
        }

        private Runnable next() throws InterruptedException {
            return Objects.requireNonNull(deliveries.poll(10, TimeUnit.SECONDS), "no update reached the render thread");
        }
    }
}
