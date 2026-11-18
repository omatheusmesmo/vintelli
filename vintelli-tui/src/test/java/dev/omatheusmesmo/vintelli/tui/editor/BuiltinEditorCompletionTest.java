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
import java.time.Duration;
import java.util.ArrayList;
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
    private static final String IMPORT_TEXT = importText("Ar");
    private static final Replacement ARRAY_LIST_IMPORT = new Replacement(1, 0, 1, 0, "import java.util.ArrayList;\n");
    private static final Replacement LINKED_LIST_IMPORT = new Replacement(1, 0, 1, 0,
            "import java.util.LinkedList;\n");

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

    private static String importText(String typed) {
        return "package demo;\n\nclass Demo {\n    void m() {\n        " + typed + "\n    }\n}\n";
    }

    @Test
    void anImportInTheAdditionalEditsGoesInWithTheNameAsOneUndoStep() throws Exception {
        Path file = write("Import.java", IMPORT_TEXT);
        Suggestion arrayList = new Suggestion("ArrayList", Kind.CLASS, "java.util", null, null, false, null, null,
                List.of(ARRAY_LIST_IMPORT), null);
        Session session = new Session(file, 4, (call, content) -> CapabilityResult.ok(List.of(arrayList)));
        session.editor.document().position(4, 10);
        session.controller.openAtCaret();
        session.answer();

        assertEquals(Optional.of("Completion inserted: ArrayList"),
                session.controller.press(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals("import java.util.ArrayList;", session.line(1));
        assertEquals("        ArrayList", session.line(5));
        assertEquals(5, session.editor.document().row(), "the caret stays on the name below the new import line");
        assertEquals(8 + "ArrayList".length(), session.editor.document().column(), "the caret ends after the name");
        assertTrue(session.editor.activeDocument().orElseThrow().dirty());

        assertTrue(session.editor.undo());
        assertEquals(IMPORT_TEXT, session.editor.document().text());
        assertFalse(session.editor.undo(), "the name and its import are one undo step");
    }

    @Test
    void severalAdditionalEditsAboveTheNameAllApplyAndTheCaretFollowsTheName() throws Exception {
        Path file = write("Several.java", IMPORT_TEXT);
        Suggestion arrayList = new Suggestion("ArrayList", Kind.CLASS, "java.util", null, null, false, null, null,
                List.of(ARRAY_LIST_IMPORT, new Replacement(2, 0, 2, 0, "import java.util.LinkedList;\n")), null);
        Session session = new Session(file, 4, (call, content) -> CapabilityResult.ok(List.of(arrayList)));
        session.editor.document().position(4, 10);
        session.controller.openAtCaret();
        session.answer();

        session.controller.press(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals("import java.util.ArrayList;", session.line(1));
        assertEquals("import java.util.LinkedList;", session.line(3));
        assertEquals("        ArrayList", session.line(6));
        assertEquals(6, session.editor.document().row(), "the caret follows the name past both new lines");
        assertEquals(8 + "ArrayList".length(), session.editor.document().column());
        assertTrue(session.editor.undo());
        assertEquals(IMPORT_TEXT, session.editor.document().text());
        assertFalse(session.editor.undo());
    }

    @Test
    void anImportOnlyResolvedOnInsertGoesInWhenTheResolveAnswers() throws Exception {
        Path file = write("Resolved.java", importText("Li"));
        Suggestion linkedList = new Suggestion("LinkedList", Kind.CLASS, "java.util", null, null, false, null, null,
                List.of(), "linked");
        List<String> resolved = new ArrayList<>();
        Session session = new Session(file, 4, (call, content) -> CapabilityResult.ok(List.of(linkedList)),
                (token, timeout) -> {
                    resolved.add(token);
                    return CapabilityResult.ok(List.of(LINKED_LIST_IMPORT));
                });
        session.editor.document().position(4, 10);
        session.controller.openAtCaret();
        session.answer();

        assertEquals(Optional.of("Resolving the import of LinkedList..."),
                session.controller.press(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals("        Li", session.line(4), "the name waits for the import");
        session.answer();

        assertEquals(List.of("Completion inserted with its import: LinkedList"), session.statuses);
        assertEquals(List.of("linked"), resolved);
        assertEquals("import java.util.LinkedList;", session.line(1));
        assertEquals("        LinkedList", session.line(5));
        assertTrue(session.editor.undo());
        assertEquals(importText("Li"), session.editor.document().text());
        assertFalse(session.editor.undo());
    }

    @Test
    void aResolveThatTimesOutInsertsTheNameWithoutTheImportAndSaysSo() throws Exception {
        Path file = write("Timeout.java", importText("Ha"));
        Suggestion hanging = new Suggestion("HangingList", Kind.CLASS, "java.util", null, null, false, null, null,
                List.of(), "hang");
        Session session = new Session(file, 4, (call, content) -> CapabilityResult.ok(List.of(hanging)),
                (token, timeout) -> CapabilityResult.failed(new CapabilityFailure("timeout",
                        "the language server did not answer completionItem/resolve within 1000 ms")));
        session.editor.document().position(4, 10);
        session.controller.openAtCaret();
        session.answer();

        assertEquals(Optional.of("Resolving the import of HangingList..."),
                session.controller.press(KeyEvent.ofKey(KeyCode.ENTER)));
        session.answer();

        assertEquals(List.of("Inserted without the import: the language server did not answer"
                + " completionItem/resolve within 1000 ms"), session.statuses);
        assertEquals("        HangingList", session.line(4));
        assertFalse(session.editor.document().text().contains("import"), "no import is added");
    }

    @Test
    void anAnswerThatArrivesAfterTheTextChangedDropsTheInsert() throws Exception {
        Path file = write("Late.java", importText("Li"));
        CountDownLatch release = new CountDownLatch(1);
        Suggestion linkedList = new Suggestion("LinkedList", Kind.CLASS, "java.util", null, null, false, null, null,
                List.of(), "linked");
        Session session = new Session(file, 4, (call, content) -> CapabilityResult.ok(List.of(linkedList)),
                (token, timeout) -> {
                    await(release);
                    return CapabilityResult.ok(List.of(LINKED_LIST_IMPORT));
                });
        session.editor.document().position(4, 10);
        session.controller.openAtCaret();
        session.answer();
        session.controller.press(KeyEvent.ofKey(KeyCode.ENTER));

        session.type('x');
        release.countDown();
        session.answer();

        assertEquals(List.of("Completion dropped: the text changed while its import was resolved"),
                session.statuses);
        assertEquals("        Lix", session.line(4));
        assertFalse(session.editor.document().text().contains("import"));
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
        private final List<String> statuses = new ArrayList<>();

        Session(Path file, int row, BiFunction<Integer, String, CapabilityResult<List<Suggestion>>> answer) {
            this(file, row, answer, (token, timeout) -> CapabilityResult.failed(
                    new CapabilityFailure("unavailable", "this session cannot resolve suggestions")));
        }

        Session(Path file, int row, BiFunction<Integer, String, CapabilityResult<List<Suggestion>>> answer,
                BiFunction<String, Duration, CapabilityResult<List<Replacement>>> resolver) {
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, row));
            provider = new CompletionFixtures.Provider(answer, resolver);
            CapabilityRegistry registry = answer == null
                    ? CompletionFixtures.registryWith(CapabilitySelection.unavailable(CapabilityType.COMPLETION,
                            "No completion provider is installed"))
                    : CompletionFixtures.registryWith(CapabilitySelection.selected(CapabilityType.COMPLETION, provider));
            controller = new CompletionController(registry, editor, deliveries::add, statuses::add);
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
