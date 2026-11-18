package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Replacement;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability.Documentation;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * The quick documentation popup against a real editor: the request carries the text the editor holds, an answer
 * for the caret it was asked for is shown, and an answer that arrives after the caret or the text moved is dropped.
 * The deliveries are queued and run by the test, so the order of the answer and the edit is fixed by the test.
 */
class QuickDocumentationControllerTest {

    private static final String TEXT = "class Demo {\n    void m() {\n        length();\n    }\n}\n";

    @TempDir
    Path project;

    @Test
    void theAnswerForTheCaretIsShownAndTheRequestCarriesTheUnsavedText() throws Exception {
        Session session = new Session(new QuickDocumentationFixtures.Provider(
                CapabilityResult.ok(new Documentation("int length()"))));
        session.editor.applyCompletion(session.file, new Replacement(2, 8, 2, 8, "x"), List.of(), 1);

        session.controller.open(session.site());
        session.drain();

        assertEquals(QuickDocumentationMenu.State.DOCUMENTED, session.controller.view().state());
        assertTrue(session.provider.contents().get(0).contains("        xlength();"), session.provider.contents().toString());
        assertEquals(List.of("2:9"), session.provider.positions(), "the caret is after the inserted x");
    }

    @Test
    void theEditorYieldsOnlyThePopupKeysWhileThePopupIsOpen() throws Exception {
        Session session = new Session(new QuickDocumentationFixtures.Provider(
                CapabilityResult.ok(new Documentation("int length()"))));

        session.controller.open(session.site());
        session.drain();

        assertTrue(session.editor.quickDocumentationTakesKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertTrue(session.editor.quickDocumentationTakesKey(KeyEvent.ofKey(KeyCode.PAGE_UP)));
        assertTrue(session.editor.quickDocumentationTakesKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertFalse(session.editor.quickDocumentationTakesKey(KeyEvent.ofChar('x')),
                "typed text is the editor's, the popup closes after it");

        session.editor.quickDocumentationKeyHandled();
        assertFalse(session.controller.isOpen(), "a key the editor handled closes the popup");
        assertFalse(session.editor.quickDocumentationTakesKey(KeyEvent.ofKey(KeyCode.DOWN)),
                "a closed popup takes no key");
    }

    @Test
    void anAnswerThatArrivesAfterTheCaretMovedIsDroppedAndThePopupCloses() throws Exception {
        Session session = new Session(new QuickDocumentationFixtures.Provider(
                CapabilityResult.ok(new Documentation("int length()"))));
        CompletionSite asked = session.site();

        session.controller.open(asked);
        session.editor.goToLine(0, 3);
        assertNotEquals(asked, session.site(), "the caret really moved before the answer arrived");
        session.drain();

        assertFalse(session.controller.isOpen(), "a stale answer closes the popup instead of showing it");
    }

    @Test
    void anAnswerThatArrivesAfterTheTextChangedIsDropped() throws Exception {
        Session session = new Session(new QuickDocumentationFixtures.Provider(
                CapabilityResult.ok(new Documentation("int length()"))));

        session.controller.open(session.site());
        session.editor.applyCompletion(session.file, new Replacement(2, 8, 2, 8, "x"), List.of(), 1);
        session.drain();

        assertFalse(session.controller.isOpen(), "an edit makes the answer describe text that is gone");
    }

    @Test
    void anEmptyAnswerIsShownAsNoDocumentationNotAsAnError() throws Exception {
        Session session = new Session(new QuickDocumentationFixtures.Provider(
                CapabilityResult.ok(new Documentation(""))));

        session.controller.open(session.site());
        session.drain();

        assertEquals(QuickDocumentationMenu.State.EMPTY, session.controller.view().state());
    }

    @Test
    void aFailedServerSaysWhyAndTheEditorKeepsItsText() throws Exception {
        Session session = new Session(new QuickDocumentationFixtures.Provider(
                CapabilityResult.failed(new CapabilityFailure("timeout", "the server did not answer within 5 s"))));

        session.controller.open(session.site());
        session.drain();

        assertEquals(QuickDocumentationMenu.State.FAILED, session.controller.view().state());
        assertEquals("the server did not answer within 5 s", session.controller.view().message());
        assertEquals(TEXT, session.editor.activeDocument().orElseThrow().text());
    }

    @Test
    void aMissingHoverProviderIsUnavailableWithItsReason() throws Exception {
        Session session = new Session(null, CapabilitySelection.unavailable(CapabilityType.HOVER,
                "the server is not running"));

        session.controller.open(session.site());
        session.drain();

        assertEquals(QuickDocumentationMenu.State.UNAVAILABLE, session.controller.view().state());
        assertEquals("the server is not running", session.controller.view().message());
    }

    /**
     * An editor with one open Java file, and a popup controller whose deliveries the test drains.
     */
    private final class Session {

        private final BuiltinEditor editor;
        private final Path file;
        private final QuickDocumentationFixtures.Provider provider;
        private final QuickDocumentationController controller;
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();

        Session(QuickDocumentationFixtures.Provider provider) throws IOException {
            this(provider, null);
        }

        Session(QuickDocumentationFixtures.Provider provider, CapabilitySelection<HoverCapability> selection)
                throws IOException {
            file = write("Demo.java", TEXT);
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, 2));
            this.provider = provider;
            CapabilitySelection<HoverCapability> chosen = selection != null ? selection
                    : CapabilitySelection.selected(CapabilityType.HOVER, provider);
            controller = new QuickDocumentationController(QuickDocumentationFixtures.registryWith(chosen), editor,
                    deliveries::add);
            editor.attachQuickDocumentation(controller);
        }

        CompletionSite site() {
            return editor.completionSite().orElseThrow();
        }

        void drain() throws InterruptedException {
            Runnable delivery = deliveries.poll(5, TimeUnit.SECONDS);
            if (delivery == null) {
                throw new AssertionError("no answer was delivered to the render thread");
            }
            delivery.run();
        }

        private Path write(String name, String contents) throws IOException {
            Path path = project.resolve(name);
            Files.writeString(path, contents);
            return path.toRealPath();
        }
    }
}
