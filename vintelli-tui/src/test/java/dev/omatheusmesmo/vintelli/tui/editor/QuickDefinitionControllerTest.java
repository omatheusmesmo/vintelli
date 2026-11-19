package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Replacement;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * The quick definition popup against a real editor: the declaration is read from a file the editor does not have
 * open or from the unsaved text of a tab that is open, the editor keeps its caret and tabs while the popup shows it,
 * and Enter hands back the target to open. The deliveries are queued and run by the test, so the order of the answer
 * and the edit is fixed by the test.
 */
class QuickDefinitionControllerTest {

    private static final String CALLER = "class Demo {\n    void m() {\n        Util.run();\n    }\n}\n";
    private static final String UTIL = """
            class Util {
                /** Runs. */
                static void run() {
                    System.out.println("ran");
                }

                static void stop() {
                }
            }
            """;

    @TempDir
    Path project;

    @BeforeEach
    void writeFiles() throws IOException {
        Files.writeString(project.resolve("Demo.java"), CALLER);
        Files.writeString(project.resolve("Util.java"), UTIL);
    }

    @Test
    void thePopupShowsTheDeclarationBodyAndTheEditorKeepsItsCaretAndTabs() throws Exception {
        Session session = new Session(List.of(new Location(util(), 2, 16)));
        CompletionSite before = session.site();

        session.controller.open(before);
        session.drain();

        QuickDefinitionMenu.View view = session.controller.view();
        assertEquals(QuickDefinitionMenu.State.PEEKING, view.state());
        QuickDefinitionMenu.Peek peek = view.current();
        assertEquals(1, peek.startLine(), "the declaration starts at its Javadoc");
        assertEquals(2, peek.line());
        assertTrue(peek.code().contains("System.out.println(\"ran\");"), peek.code());
        assertFalse(peek.code().contains("stop"), "the next method is not part of the declaration: " + peek.code());
        assertEquals("Util.java:3", peek.title());
        assertEquals(before, session.site(), "the caret and the text are the ones the popup was asked for");
        assertEquals(1, session.editor.tabs().size(), "peeking opens no tab");
        assertEquals(List.of("Definition at Util.java:3"), session.statuses);
    }

    @Test
    void enterHandsBackTheTargetAndClosesThePopup() throws Exception {
        Session session = new Session(List.of(new Location(util(), 2, 16)));
        session.controller.open(session.site());
        session.drain();

        Optional<QuickDefinitionController.Target> target = session.controller.press(OverlayKey.ENTER);

        assertEquals(Optional.of(new QuickDefinitionController.Target(util(), 2)), target);
        assertFalse(session.controller.isOpen());
    }

    @Test
    void escapeClosesThePopupWithoutATarget() throws Exception {
        Session session = new Session(List.of(new Location(util(), 2, 16)));
        CompletionSite before = session.site();
        session.controller.open(before);
        session.drain();

        Optional<QuickDefinitionController.Target> target = session.controller.press(OverlayKey.ESCAPE);

        assertTrue(target.isEmpty());
        assertFalse(session.controller.isOpen());
        assertEquals(before, session.site());
    }

    @Test
    void severalTargetsArePagedAndTheShownOneIsTheOneThatOpens() throws Exception {
        Session session = new Session(List.of(new Location(util(), 2, 16), new Location(util(), 6, 16)));
        session.controller.open(session.site());
        session.drain();
        assertEquals(2, session.controller.view().peeks().size());

        session.controller.next();
        assertEquals(1, session.controller.view().index());
        assertTrue(session.controller.view().current().code().contains("static void stop()"));

        session.controller.next();
        assertEquals(0, session.controller.view().index(), "paging wraps around");
        session.controller.previous();
        assertEquals(Optional.of(new QuickDefinitionController.Target(util(), 6)), session.controller.accept());
    }

    @Test
    void anOpenTabIsShownWithItsUnsavedText() throws Exception {
        Session session = new Session(List.of(new Location(util(), 2, 16)));
        session.editor.open(new OpenRequest(util(), 3));
        session.editor.applyCompletion(util(), new Replacement(3, 8, 3, 8, "edited_"), List.of(), 7);
        session.editor.open(new OpenRequest(session.file, 2));

        session.controller.open(session.site());
        session.drain();

        String code = session.controller.view().current().code();
        assertTrue(code.contains("edited_System.out.println"), "the tab is read, not the file on disk: " + code);
        assertEquals(session.file, Path.of(session.editor.activeDocument().orElseThrow().file()));
    }

    @Test
    void aLibraryLocationSaysWhyItCannotBeShown() throws Exception {
        Path library = Path.of("jdt://contents/java.base/java.lang/String.class");
        Session session = new Session(List.of(new Location(library, 10, 4)));

        session.controller.open(session.site());
        session.drain();

        QuickDefinitionMenu.View view = session.controller.view();
        assertEquals(QuickDefinitionMenu.State.FAILED, view.state());
        assertTrue(view.message().contains("jdt://") && view.message().contains("String.class"), view.message());
        assertEquals(List.of(view.message()), session.statuses, "the status line explains it too");
    }

    @Test
    void aTargetWithNoReadableFileSaysWhyAndKeepsTheOtherTargets() throws Exception {
        Path missing = project.resolve("Missing.java");
        Session session = new Session(List.of(new Location(missing, 1, 1), new Location(util(), 2, 16)));

        session.controller.open(session.site());
        session.drain();

        assertEquals(1, session.controller.view().peeks().size(), "the readable target is still shown");
    }

    @Test
    void anEmptyAnswerSaysNoDefinitionWasFound() throws Exception {
        Session session = new Session(List.of());

        session.controller.open(session.site());
        session.drain();

        assertEquals(QuickDefinitionMenu.State.FAILED, session.controller.view().state());
        assertEquals("No definition found", session.controller.view().message());
    }

    @Test
    void aFailedServerSaysWhy() throws Exception {
        Session session = new Session(CapabilityResult.failed(new CapabilityFailure("timeout", "no answer in 5 s")));

        session.controller.open(session.site());
        session.drain();

        assertEquals("timeout: no answer in 5 s", session.controller.view().message());
    }

    @Test
    void aMissingNavigationProviderIsExplained() throws Exception {
        Session session = new Session((CapabilityResult<List<Location>>) null);

        session.controller.open(session.site());
        session.drain();

        assertEquals("Quick definition unavailable: Navigation provider unavailable",
                session.controller.view().message());
    }

    @Test
    void anAnswerThatArrivesAfterTheCaretMovedIsDroppedAndThePopupCloses() throws Exception {
        Session session = new Session(List.of(new Location(util(), 2, 16)));
        CompletionSite asked = session.site();

        session.controller.open(asked);
        session.editor.goToLine(0, 3);
        assertNotEquals(asked, session.site());
        session.drain();

        assertFalse(session.controller.isOpen());
        assertTrue(session.statuses.isEmpty(), "a dropped answer reports nothing");
    }

    @Test
    void theEditorYieldsOnlyThePopupKeysWhileThePopupIsOpen() throws Exception {
        Session session = new Session(List.of(new Location(util(), 2, 16)));
        session.controller.open(session.site());
        session.drain();

        assertTrue(session.editor.quickDefinitionTakesKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertTrue(session.editor.quickDefinitionTakesKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertTrue(session.editor.quickDefinitionTakesKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertFalse(session.editor.quickDefinitionTakesKey(KeyEvent.ofChar('x')),
                "typed text is the editor's, the popup closes after it");

        session.editor.quickDefinitionKeyHandled();
        assertFalse(session.controller.isOpen());
        assertFalse(session.editor.quickDefinitionTakesKey(KeyEvent.ofKey(KeyCode.DOWN)), "a closed popup takes no key");
    }

    private Path util() throws IOException {
        return project.resolve("Util.java").toRealPath();
    }

    private final class Session {

        private final BuiltinEditor editor;
        private final Path file;
        private final QuickDefinitionController controller;
        private final List<String> statuses = new ArrayList<>();
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();

        Session(List<Location> locations) throws IOException {
            this(CapabilityResult.ok(locations));
        }

        Session(CapabilityResult<List<Location>> definition) throws IOException {
            file = project.resolve("Demo.java").toRealPath();
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, 2));
            NavigationCapability navigation = definition == null ? null
                    : new QuickDefinitionFixtures.Navigation(definition,
                            List.of(QuickDefinitionFixtures.method("run", 1, 4),
                                    QuickDefinitionFixtures.method("stop", 6, 7)));
            controller = new QuickDefinitionController(() -> navigation,
                    QuickDefinitionFixtures.registryReadingFiles(), editor, deliveries::add, statuses::add);
            editor.attachQuickDefinition(controller);
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
    }
}
