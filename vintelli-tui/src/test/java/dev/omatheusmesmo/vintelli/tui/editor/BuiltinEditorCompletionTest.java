package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Kind;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Replacement;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * The completion popup driven headlessly through the built-in editor: a request runs off the render thread,
 * typing narrows the list while the editor keeps the text, an insert is one undo step, and a server that
 * cannot answer says why without blocking the editor.
 */
class BuiltinEditorCompletionTest {

    private static final String JAVA_TEXT = "class Demo {\n    void m() {\n        text\n    }\n}\n";
    private static final String DOT_TEXT = "class Demo {\n    void m() {\n        text.\n    }\n}\n";
    private static final Suggestion LENGTH = new Suggestion("length", Kind.METHOD, "int",
            new Replacement(2, 13, 2, 13, "length()"), null, false, null, "0001");
    private static final String BLANK_LINE_TEXT = "class Demo {\n    void m() {\n        \n    }\n}\n";

    @TempDir
    Path project;

    @Test
    void ctrlSpaceReturnsAtOnceAndEnterInsertsTheTextEditAsOneUndoStep() throws Exception {
        Path file = write("Demo.java", DOT_TEXT);
        CountDownLatch release = new CountDownLatch(1);
        Session session = new Session(file, 2, (call, content) -> {
            await(release);
            return CapabilityResult.ok(List.of(LENGTH));
        });
        session.editor.document().position(2, 13);

        assertEquals("Looking for completions...", session.controller.openAtCaret());
        assertTrue(session.controller.isOpen(), "the popup opens before the server answers");
        assertEquals(CompletionMenu.State.LOADING, session.controller.view().state());

        release.countDown();
        session.answer();
        assertEquals(CompletionMenu.State.ITEMS, session.controller.view().state());

        assertEquals(Optional.of("Completion inserted: length"),
                session.controller.press(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals("        text.length()", session.line(2));
        assertEquals(21, session.editor.document().column(), "the caret ends after the inserted text");
        assertTrue(session.editor.activeDocument().orElseThrow().dirty());
        assertFalse(session.controller.isOpen());

        assertTrue(session.editor.undo());
        assertEquals("        text.", session.line(2));
        assertFalse(session.editor.activeDocument().orElseThrow().dirty());
        assertFalse(session.editor.undo(), "the whole insert is one undo step");
    }

    @Test
    void typingNarrowsTheListBackspaceWidensItAndANoMatchClosesThePopup() throws Exception {
        Path file = write("Narrow.java", JAVA_TEXT);
        Session session = new Session(file, 2, (call, content) -> CapabilityResult.ok(List.of(
                suggestion("textValue"), suggestion("test"), suggestion("length"))));
        session.editor.document().position(2, 10);
        session.controller.openAtCaret();
        session.answer();
        assertEquals(List.of("textValue", "test"), labels(session), "te matches textValue and test, in server order");

        session.type('x');
        assertEquals(List.of("textValue"), labels(session), "texts narrows to textValue");

        session.backspace();
        assertEquals(List.of("textValue", "test"), labels(session), "Backspace widens the list again");

        session.type('q');
        session.type('z');
        assertFalse(session.controller.isOpen(), "a prefix nothing matches closes the popup");
    }

    @Test
    void enterAndTabInsertTheSnippetAsPlainTextWithTheCaretAtTheFirstStop() throws Exception {
        Path file = write("Snippet.java", "class Demo {\n    void m() {\n        al\n    }\n}\n");
        Suggestion alpha = new Suggestion("alpha", Kind.METHOD, "", null, "alpha(${1:value}) $0", true, null, null);
        Session session = new Session(file, 2, (call, content) -> CapabilityResult.ok(List.of(alpha)));
        session.editor.document().position(2, 10);
        session.controller.openAtCaret();
        session.answer();

        assertEquals(Optional.of("Completion inserted: alpha"),
                session.controller.press(KeyEvent.ofKey(KeyCode.TAB)));

        assertEquals("        alpha(value) ", session.line(2));
        assertEquals(8 + "alpha(".length(), session.editor.document().column(),
                "the caret sits on the placeholder text");
        assertTrue(session.editor.undo());
        assertEquals("        al", session.line(2));
    }

    @Test
    void aStaleAnswerIsDroppedAndTheCompletionIsAskedAgainForTheCurrentText() throws Exception {
        Path file = write("Stale.java", BLANK_LINE_TEXT);
        CountDownLatch release = new CountDownLatch(1);
        Session session = new Session(file, 2, (call, content) -> {
            if (call == 0) {
                await(release);
                return CapabilityResult.ok(List.of(suggestion("stale")));
            }
            return CapabilityResult.ok(List.of(suggestion("abcFresh"), suggestion("other")));
        });
        session.editor.document().position(2, 8);
        session.controller.openAtCaret();
        session.type('a');
        session.type('b');
        session.type('c');

        release.countDown();
        session.answer();
        session.answer();

        assertEquals(List.of("abcFresh"), labels(session), "the answer of the old text never shows");
        assertTrue(session.provider.contents().getLast().contains("        abc\n"),
                "the second request carries the text the user has now");
    }

    @Test
    void anUnavailableServerSaysWhyAndTheEditorKeepsEditing() throws Exception {
        Path file = write("Unavailable.java", JAVA_TEXT);
        Session session = new Session(file, 2, null);
        session.editor.document().position(2, 12);

        session.controller.openAtCaret();
        session.answer();

        assertEquals(CompletionMenu.State.UNAVAILABLE, session.controller.view().state());
        assertEquals("No completion provider is installed", session.controller.view().message());
        session.type('x');
        assertEquals("        textx", session.line(2), "the editor still types");
        assertEquals(Optional.of(""), session.controller.press(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertFalse(session.controller.isOpen());
    }

    @Test
    void aFailedServerAnswerIsReportedAsFailedWithItsDetail() throws Exception {
        Path file = write("Failed.java", JAVA_TEXT);
        Session session = new Session(file, 2, (call, content) -> CapabilityResult.failed(
                new CapabilityFailure("malformed", "the server sent an item without a label")));
        session.editor.document().position(2, 12);

        session.controller.openAtCaret();
        session.answer();

        assertEquals(CompletionMenu.State.FAILED, session.controller.view().state());
        assertEquals("the server sent an item without a label", session.controller.view().message());
    }

    @Test
    void aDotBehindAnIdentifierOpensTheListOnlyInJavaFiles() throws Exception {
        Path java = write("Dot.java", JAVA_TEXT);
        Session javaSession = new Session(java, 2, (call, content) -> CapabilityResult.ok(List.of(LENGTH)));
        javaSession.editor.document().position(2, 12);
        javaSession.type('.');
        assertTrue(javaSession.controller.isOpen(), "a dot behind text in a .java file opens the list");

        Path plain = write("Dot.txt", JAVA_TEXT);
        Session textSession = new Session(plain, 2, (call, content) -> CapabilityResult.ok(List.of(LENGTH)));
        textSession.editor.document().position(2, 12);
        textSession.type('.');
        assertFalse(textSession.controller.isOpen(), "only Java files open the list on a dot");

        Path spaced = write("Spaced.java", JAVA_TEXT);
        Session spacedSession = new Session(spaced, 2, (call, content) -> CapabilityResult.ok(List.of(LENGTH)));
        spacedSession.editor.document().position(2, 8);
        spacedSession.type('.');
        assertFalse(spacedSession.controller.isOpen(), "a dot after a blank does not follow an identifier");
    }

    @Test
    void movingTheCaretOutOfTheCompletedTextClosesThePopup() throws Exception {
        Path file = write("Caret.java", JAVA_TEXT);
        Session session = new Session(file, 2, (call, content) -> CapabilityResult.ok(List.of(LENGTH)));
        session.editor.document().position(2, 12);
        session.controller.openAtCaret();
        session.answer();

        session.editor.document().position(2, 0);
        session.controller.afterEditorKey(KeyEvent.ofKey(KeyCode.LEFT));

        assertFalse(session.controller.isOpen());
    }

    private Path write(String name, String contents) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, contents);
        return file.toRealPath();
    }

    private static Suggestion suggestion(String label) {
        return new Suggestion(label, Kind.METHOD, "", null, null, false, null, null);
    }

    private static List<String> labels(Session session) {
        return session.controller.view().rows().stream().map(Suggestion::label).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the test never released the server");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * An open document with a completion controller whose updates go to a queue the test drains, and a provider
     * that answers through {@code answer} with the index of the call and the content it was asked about.
     */
    private final class Session {

        private final BuiltinEditor editor;
        private final CompletionFixtures.Provider provider;
        private final CompletionController controller;
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();

        Session(Path file, int row, BiFunction<Integer, String, CapabilityResult<List<Suggestion>>> answer) {
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, row));
            provider = new CompletionFixtures.Provider(answer);
            CapabilityRegistry registry = answer == null
                    ? CompletionFixtures.registryWith(CapabilitySelection.unavailable(CapabilityType.COMPLETION,
                            "No completion provider is installed"))
                    : CompletionFixtures.registryWith(CapabilitySelection.selected(CapabilityType.COMPLETION, provider));
            controller = new CompletionController(registry, editor, deliveries::add);
        }

        void answer() throws InterruptedException {
            Runnable update = deliveries.poll(10, TimeUnit.SECONDS);
            assertNotNull(update, "no update reached the render thread");
            update.run();
        }

        void type(char character) {
            editor.document().replaceSelection(String.valueOf(character));
            controller.afterEditorKey(KeyEvent.ofChar(character));
        }

        void backspace() {
            EditorDocument document = editor.document();
            int row = document.row();
            int column = document.column();
            String text = document.text();
            int start = offset(text, row, column - 1);
            document.replaceText(text.substring(0, start) + text.substring(start + 1));
            document.position(row, column - 1);
            controller.afterEditorKey(KeyEvent.ofKey(KeyCode.BACKSPACE));
        }

        String line(int row) {
            return editor.document().text().split("\n", -1)[row];
        }

        private static int offset(String text, int row, int column) {
            int offset = 0;
            String[] lines = text.split("\n", -1);
            for (int line = 0; line < row; line++) {
                offset += lines[line].length() + 1;
            }
            return offset + column;
        }
    }
}
