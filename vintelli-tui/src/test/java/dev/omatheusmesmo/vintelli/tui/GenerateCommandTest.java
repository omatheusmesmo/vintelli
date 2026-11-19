package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CommandRef;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.CodeActionPopup;
import dev.omatheusmesmo.vintelli.tui.editor.QuickFixController;
import dev.omatheusmesmo.vintelli.tui.editor.QuickFixOverlay;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The Generate popup driven headlessly: it lists the generate actions of the class at the caret, a plain edit
 * lands as one undoable step, a field prompt is listed but never runs, and an empty answer or a missing server
 * says so in the popup and on the status line.
 */
class GenerateCommandTest {

    private static final Rect AREA = new Rect(0, 0, 100, 30);
    private static final String TEXT = "one\ntwo\nthree\nfour\n";
    private static final String GENERATED = "one\ngenerated\ntwo\nthree\nfour\n";
    private static final String GETTERS = "Generate Getters and Setters";
    private static final String TO_STRING = "Generate toString()";
    private static final String TO_STRING_PROMPT = "java.action.generateToStringPrompt";

    @TempDir
    Path project;

    @Test
    void theGenerateActionsAreListedAndAPlainEditInsertsItAsOneUndoStep() throws Exception {
        Path file = write("Plain.txt", TEXT);
        Session session = new Session(file, QuickFixFixtures.answering(path -> CapabilityResult.ok(List.of(
                getters(file), organizeImports(file), quickFix(file)))));

        session.open();

        String popup = session.render();
        assertTrue(popup.contains(GETTERS), popup);
        assertFalse(popup.contains("Organize Imports"), "organize imports is not a generate action: " + popup);
        assertFalse(popup.contains("Replace line with fixed text"), "a quick fix is not listed: " + popup);
        assertEquals("Generate applied: " + GETTERS + " (1 edit)", session.controller.press(OverlayKey.ENTER));
        assertEquals(GENERATED, session.text());
        assertTrue(session.editor.undo());
        assertEquals(TEXT, session.text());
        assertFalse(session.editor.undo(), "the generated code is a single undo step");
    }

    @Test
    void aFieldPromptIsListedDimmedAndNeverApplied() throws Exception {
        Path file = write("Prompt.txt", TEXT);
        Session session = new Session(file, QuickFixFixtures.answering(path -> CapabilityResult.ok(List.of(
                toStringPrompt(), getters(file)))));

        session.open();

        assertTrue(session.render().contains(TO_STRING + " (needs field selection)"));
        assertEquals(TO_STRING + " needs field selection, which Vintelli does not offer yet",
                session.controller.press(OverlayKey.ENTER));
        assertTrue(session.controller.isOpen(), "the popup stays open so another action can be picked");
        assertEquals(TEXT, session.text());
        assertFalse(session.editor.activeDocument().orElseThrow().dirty());
    }

    @Test
    void nothingToGenerateSaysSoOnTheStatusLineAndInThePopup() throws Exception {
        Path file = write("Nothing.txt", TEXT);
        Session session = new Session(file, QuickFixFixtures.answering(path -> CapabilityResult.ok(List.of(
                quickFix(file)))));

        session.open();

        assertEquals(List.of("Nothing to generate here"), session.statuses);
        assertTrue(session.render().contains("Nothing to generate here"));
    }

    @Test
    void noLanguageServerSaysWhyInThePopup() throws Exception {
        Path file = write("Unavailable.txt", TEXT);
        Session session = new Session(file, CapabilitySelection.unavailable(CapabilityType.CODE_ACTION,
                "no language server is configured"));

        session.open();

        assertTrue(session.render().contains("Generate unavailable: no language server is configured"));
        assertTrue(session.statuses.isEmpty(), "the reason is shown in the popup, not as a status");
    }

    @Test
    void aFailedRequestSaysWhyInThePopup() throws Exception {
        Path file = write("Failed.txt", TEXT);
        Session session = new Session(file, QuickFixFixtures.answering(path -> QuickFixFixtures.failure("malformed",
                "the server sent a bad action")));

        session.open();

        assertTrue(session.render().contains("Generate failed: malformed: the server sent a bad action"));
    }

    private static CodeAction getters(Path file) {
        return new CodeAction(GETTERS, "source.generate.accessors", false, edit(file, new TextEdit(1, 0, 1, 0,
                "generated\n")), null);
    }

    private static CodeAction organizeImports(Path file) {
        return new CodeAction("Organize Imports", "source.organizeImports", false, edit(file, new TextEdit(1, 0, 1, 0,
                "")), null);
    }

    private static CodeAction quickFix(Path file) {
        return new CodeAction("Replace line with fixed text", "quickfix", true, edit(file, new TextEdit(2, 0, 2, 3,
                "fixed")), null);
    }

    private static CodeAction toStringPrompt() {
        return new CodeAction(TO_STRING, "source.generate.toString", false, null,
                new CommandRef(TO_STRING_PROMPT, TO_STRING));
    }

    private static WorkspaceEdit edit(Path file, TextEdit text) {
        return new WorkspaceEdit(List.of(new DocumentEdit(file, List.of(text))), List.of());
    }

    private Path write(String name, String contents) throws IOException {
        return Files.writeString(project.resolve(name), contents).toRealPath();
    }

    /**
     * An editor with the file open at its second line, and a Generate popup whose answer is delivered to a
     * queue the test drains on its own thread, the way the render thread does.
     */
    private final class Session {

        private final BuiltinEditor editor;
        private final QuickFixController controller;
        private final QuickFixOverlay overlay;
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        private final List<String> statuses = new ArrayList<>();

        Session(Path file, CapabilitySelection<CodeActionCapability> codeActions) {
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, 1));
            controller = new QuickFixController(CodeActionPopup.GENERATE,
                    QuickFixFixtures.registryWith(codeActions), editor, deliveries::add, statuses::add);
            overlay = new QuickFixOverlay(controller);
        }

        void open() throws InterruptedException {
            controller.open(editor.quickFixRequest().orElseThrow());
            Objects.requireNonNull(deliveries.poll(10, TimeUnit.SECONDS), "no answer reached the render thread").run();
        }

        String render() {
            Buffer buffer = Buffer.empty(AREA);
            overlay.render(Frame.forTesting(buffer), AREA, Optional.empty());
            StringBuilder rows = new StringBuilder();
            for (int y = 0; y < AREA.height(); y++) {
                for (int x = 0; x < AREA.width(); x++) {
                    rows.append(buffer.get(x, y).symbol());
                }
                rows.append('\n');
            }
            return rows.toString();
        }

        String text() {
            return editor.activeDocument().orElseThrow().text();
        }
    }
}
