package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.RenameCapability;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rename Symbol driven headlessly: the server's suggestion fills the input, a rejected position closes it and says
 * so, a missing server says why, and a confirmed name renames the symbol in every document it touches.
 */
class RenameControllerTest {

    private static final String DECLARATION = "class Greeter {\n    void greet() {}\n}\n";
    private static final String CALLER = "class Caller {\n    void run() { greet(); }\n}\n";
    private static final int DECLARATION_LINE = 1;
    private static final int DECLARATION_COLUMN = 9;
    private static final String RENAMED = "class Greeter {\n    void salute() {}\n}\n";

    @TempDir
    Path project;

    @Test
    void aPositionTheServerRejectsClosesTheInputAndSaysItCannotBeRenamed() throws Exception {
        Session session = new Session(offering(RenameFixtures.answering(CapabilityResult.failed(
                new CapabilityFailure("not-resolvable", "the position does not name a symbol that can be renamed")),
                null)));

        session.open();
        session.awaitDelivery();

        assertEquals(RenameController.NOT_RENAMEABLE, session.status.get());
        assertFalse(session.overlay.isOpen());
        assertFalse(session.editor.overlayInputActive());
    }

    @Test
    void aMissingServerSaysWhyTheRenameCannotRun() throws Exception {
        String reason = "No language server found. Install jdtls and put it on the PATH";
        Session session = new Session(CapabilitySelection.unavailable(CapabilityType.RENAME, reason));

        session.open();
        session.awaitDelivery();

        assertEquals("Rename failed: " + reason, session.status.get());
        assertFalse(session.overlay.isOpen());
    }

    @Test
    void theSuggestedNameIsWhatAConfirmedInputRenamesTo() throws Exception {
        Session session = new Session(offering(RenameFixtures.answering(CapabilityResult.ok("salute"), null)));

        session.open();
        session.awaitDelivery();

        assertEquals(Optional.of("salute"), session.overlay.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void aConfirmedNameRenamesTheSymbolInEveryDocumentAndSaysWhatChanged() throws Exception {
        Path greeter = project.resolve("Greeter.java");
        Files.writeString(greeter, DECLARATION);
        Path caller = project.resolve("Caller.java");
        Files.writeString(caller, CALLER);
        WorkspaceEdit edit = new WorkspaceEdit(List.of(
                new DocumentEdit(greeter.toRealPath(),
                        List.of(new TextEdit(DECLARATION_LINE, 9, DECLARATION_LINE, 14, "salute"))),
                new DocumentEdit(caller.toRealPath(), List.of(new TextEdit(1, 17, 1, 22, "salute")))), List.of());
        Session session = new Session(offering(RenameFixtures.answering(CapabilityResult.ok("greet"),
                CapabilityResult.ok(edit))));
        session.open();
        session.awaitDelivery();

        session.controller.submit("salute");
        assertEquals("Renaming to 'salute'...", session.status.get());
        session.awaitDelivery();

        assertEquals("Renamed to 'salute': 2 occurrences in 2 files", session.status.get());
        assertEquals(RENAMED, session.editor.activeDocument().orElseThrow().text());
        assertEquals(2, session.editor.tabs().size());
    }

    @Test
    void aNameThatIsNotChangedCancelsWithoutAskingTheServerForEdits() throws Exception {
        Session session = new Session(offering(RenameFixtures.answering(CapabilityResult.ok("greet"),
                CapabilityResult.failed(new CapabilityFailure("unavailable", "the server must not be asked")))));
        session.open();
        session.awaitDelivery();

        session.controller.submit("greet");

        assertEquals(RenameController.UNCHANGED, session.status.get());
        assertEquals(DECLARATION, session.editor.activeDocument().orElseThrow().text());
    }

    private static CapabilitySelection<RenameCapability> offering(RenameCapability provider) {
        return CapabilitySelection.selected(CapabilityType.RENAME, provider);
    }

    /**
     * An editor with the declaration open, a controller whose updates the test drains from a queue, and the rename
     * port the test hands in, served by a registry that offers nothing else.
     */
    private final class Session {

        private final Path file;
        private final BuiltinEditor editor;
        private final RenameOverlay overlay = new RenameOverlay();
        private final RenameController controller;
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        private final AtomicReference<String> status = new AtomicReference<>("");

        Session(CapabilitySelection<RenameCapability> chosen) throws IOException {
            Path greeter = project.resolve("Greeter.java");
            Files.writeString(greeter, DECLARATION);
            file = greeter.toRealPath();
            editor = new BuiltinEditor(project, null, new ShellThemes("dark", (Path) null)::editorPalette);
            editor.open(new OpenRequest(file, DECLARATION_LINE));
            controller = new RenameController(RenameFixtures.registryWith(chosen), editor, overlay,
                    deliveries::add, status::set);
        }

        void open() {
            RenameSite site = RenameSite.at(file, DECLARATION, DECLARATION_LINE, DECLARATION_COLUMN);
            overlay.open(site.word());
            editor.overlayInputActive(true);
            controller.begin(site);
        }

        void awaitDelivery() throws InterruptedException {
            Runnable update = deliveries.poll(10, TimeUnit.SECONDS);
            assertTrue(update != null, "no update reached the render thread");
            update.run();
        }
    }
}
