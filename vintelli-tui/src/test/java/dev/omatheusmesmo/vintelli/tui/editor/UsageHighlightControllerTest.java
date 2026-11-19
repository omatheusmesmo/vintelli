package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Replacement;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentHighlight;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentHighlight.Kind;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;

/**
 * The usage highlight against a real editor drawn with the theme roles: after the debounce the read and the write
 * occurrences of the caret symbol carry their own roles, and the highlight is dropped for an answer that arrives
 * after the caret or the text moved, cleared when the caret leaves the occurrences or the text changes, and never
 * painted over a selection. The deliveries are queued and run by the test.
 */
class UsageHighlightControllerTest {

    private static final String TEXT = """
            class Demo {
                int count;
                void m() {
                    count = count + 1;
                }
                int other;
            }
            """;
    private static final int TEXT_LEFT = 4;
    private static final Rect AREA = new Rect(0, 0, 60, 10);
    private static final Duration DEBOUNCE = Duration.ofMillis(20);

    @TempDir
    Path project;

    @Test
    void afterTheDebounceTheReadAndTheWriteOccurrencesRenderWithTheirOwnRoles() throws Exception {
        Session session = new Session(DEBOUNCE);
        session.editor.goToLine(4, 10);

        session.render();
        session.drain();
        Buffer buffer = session.render();

        assertEquals(List.of("3:9"), session.provider.positions(), "the request carries the caret position");
        Color write = session.role("editor-usage-write");
        Color read = session.role("editor-usage-read");
        assertNotEquals(read, write, "the two roles tell a read from a write");
        for (int column = 8; column < 13; column++) {
            assertEquals(write, session.bg(buffer, column), "the written occurrence at column " + column);
        }
        for (int column = 16; column < 21; column++) {
            assertEquals(read, session.bg(buffer, column), "the read occurrence at column " + column);
        }
        assertNotEquals(write, session.bg(buffer, 14), "text between the occurrences is not tinted");
        assertNotEquals(read, session.bg(buffer, 14), "text between the occurrences is not tinted");
    }

    @Test
    void anAnswerForAnOlderCaretPositionIsIgnored() throws Exception {
        Session session = new Session(DEBOUNCE);
        session.editor.goToLine(4, 10);
        session.render();

        session.editor.goToLine(6, 9);
        session.drain();
        Buffer buffer = session.paintWithoutObserving();

        for (int column = 8; column < 21; column++) {
            assertFalse(isTinted(session, buffer, column), "the answer for the old caret is not drawn at " + column);
        }
    }

    @Test
    void anAnswerForTextThatChangedIsIgnored() throws Exception {
        Session session = new Session(DEBOUNCE);
        session.editor.goToLine(4, 10);
        session.render();

        session.editor.applyCompletion(session.file, new Replacement(1, 4, 1, 4, "final "), List.of(), 6);
        session.drain();
        Buffer buffer = session.paintWithoutObserving();

        for (int column = 8; column < 21; column++) {
            assertFalse(isTinted(session, buffer, column), "the answer for the old text is not drawn at " + column);
        }
    }

    @Test
    void theHighlightClearsWhenTheCaretLeavesEveryOccurrenceAndStaysWhileItMovesInside() throws Exception {
        Session session = new Session(DEBOUNCE);
        session.editor.goToLine(4, 10);
        session.render();
        session.drain();
        assertTrue(isTinted(session, session.render(), 9), "the occurrences are shown before the caret moves");

        session.editor.goToLine(4, 18);
        Buffer inside = session.render();
        assertTrue(isTinted(session, inside, 9), "a caret inside another occurrence keeps the highlight");
        assertEquals(1, session.provider.positions().size(), "moving inside the occurrences asks nothing more");

        session.editor.goToLine(6, 9);
        Buffer outside = session.render();
        assertFalse(isTinted(session, outside, 9), "a caret that left every occurrence clears the highlight");
    }

    @Test
    void aTextChangeClearsTheHighlight() throws Exception {
        Session session = new Session(DEBOUNCE);
        session.editor.goToLine(4, 10);
        session.render();
        session.drain();
        assertTrue(isTinted(session, session.render(), 9));

        session.editor.applyCompletion(session.file, new Replacement(5, 4, 5, 4, "x"), List.of(), 1);
        session.editor.goToLine(4, 10);
        Buffer buffer = session.render();

        assertFalse(isTinted(session, buffer, 9), "an edit drops the occurrences of the old text");
    }

    @Test
    void aCaretThatKeepsMovingAsksOnlyOnceItRestsAndOnlyForTheRestingPosition() throws Exception {
        Session session = new Session(Duration.ofMillis(200));
        session.editor.goToLine(4, 10);
        session.render();
        session.editor.goToLine(6, 9);
        session.render();

        session.drain();

        assertEquals(List.of("5:8"), session.provider.positions(),
                "only the position the caret rested on is requested");
    }

    @Test
    void aCaretOnWhitespaceAsksNothing() throws Exception {
        Session session = new Session(Duration.ofMillis(5));
        session.editor.goToLine(4, 3);

        session.render();
        Thread.sleep(100);

        assertEquals(List.of(), session.provider.positions());
        assertEquals(0, session.deliveries.size());
    }

    @Test
    void aSelectionKeepsItsLookOverAnOccurrence() throws Exception {
        Session session = new Session(DEBOUNCE);
        session.editor.goToLine(4, 10);
        session.render();
        session.drain();
        session.editor.document().highlighter().setSelection(new SelectionCursor(3, 8, 3, 13));

        Buffer buffer = session.render();

        Color write = session.role("editor-usage-write");
        for (int column = 8; column < 13; column++) {
            assertTrue(buffer.get(TEXT_LEFT + column, 3).style().addModifiers().contains(Modifier.REVERSED),
                    "the selection stays reversed at column " + column);
            assertNotEquals(write, session.bg(buffer, column), "the selection is not tinted at column " + column);
        }
        assertEquals(session.role("editor-usage-read"), session.bg(buffer, 16),
                "the occurrence outside the selection is still tinted");
    }

    @Test
    void aProviderThatCannotAnswerLeavesTheTextUntouched() throws Exception {
        Session session = new Session(DEBOUNCE, new Provider(CapabilityResult.failed(
                new CapabilityFailure("not-advertised", "the server has no document highlights"))));
        session.editor.goToLine(4, 10);

        session.render();
        session.drain();
        Buffer buffer = session.render();

        assertEquals(List.of("3:9"), session.provider.positions());
        assertFalse(isTinted(session, buffer, 9));
        assertFalse(isTinted(session, buffer, 17));
    }

    private static boolean isTinted(Session session, Buffer buffer, int column) {
        Color background = session.bg(buffer, column);
        return session.role("editor-usage-read").equals(background)
                || session.role("editor-usage-write").equals(background);
    }

    private static final class Provider implements NavigationCapability {

        private final CapabilityResult<List<DocumentHighlight>> answer;
        private final List<String> positions = new CopyOnWriteArrayList<>();

        Provider(CapabilityResult<List<DocumentHighlight>> answer) {
            this.answer = answer;
        }

        List<String> positions() {
            return List.copyOf(positions);
        }

        @Override
        public CapabilityResult<List<DocumentHighlight>> documentHighlights(SourcePosition position) {
            positions.add(position.line() + ":" + position.column());
            return answer;
        }

        @Override
        public CapabilityResult<List<Location>> definition(SourcePosition position) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public CapabilityResult<List<DocumentSymbol>> documentSymbols(Path file) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public CapabilityResult<List<WorkspaceSymbol>> workspaceSymbols(String query) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public String identifier() {
            return "fixture-navigation";
        }

        @Override
        public String provenance() {
            return "fixture language server";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }
    }

    private final class Session {

        private final ShellThemes themes = new ShellThemes("dark", (Path) null);
        private final BuiltinEditor editor;
        private final Path file;
        private final Provider provider;
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        private final UsageHighlightController controller;

        Session(Duration debounce) throws IOException {
            this(debounce, new Provider(CapabilityResult.ok(List.of(
                    new DocumentHighlight(new SourceRange(3, 8, 3, 13), Kind.WRITE),
                    new DocumentHighlight(new SourceRange(3, 16, 3, 21), Kind.READ)))));
        }

        Session(Duration debounce, Provider provider) throws IOException {
            this.provider = provider;
            file = project.toRealPath().resolve("Demo.java");
            Files.writeString(file, TEXT);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, 0));
            controller = new UsageHighlightController(() -> provider, editor, deliveries::add, debounce);
            editor.attachUsageHighlights(controller);
        }

        Buffer paintWithoutObserving() {
            Buffer buffer = Buffer.empty(AREA);
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(themes.engine());
            controller.paint(buffer, new Rect(TEXT_LEFT, 0, AREA.width() - TEXT_LEFT, AREA.height() - 1),
                    editor.document(), context);
            return buffer;
        }

        Buffer render() {
            Buffer buffer = Buffer.empty(AREA);
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(themes.engine());
            editor.element().render(Frame.forTesting(buffer), AREA, context);
            return buffer;
        }

        Color role(String role) {
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(themes.engine());
            Color background = EditorElement.roleStyle(context, role).bg().orElse(null);
            assertNotNull(background, "the theme defines the " + role + " role with a background");
            return background;
        }

        Color bg(Buffer buffer, int column) {
            return buffer.get(TEXT_LEFT + column, 3).style().bg().orElse(null);
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
