package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.ExtractController;
import dev.omatheusmesmo.vintelli.tui.editor.ExtractController.Extraction;
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
import java.util.function.Function;

/**
 * The extract refactorings driven headlessly against a fake code action provider: the one action of the requested
 * kind lands as one undoable step with the caret on the new name, and nothing extractable, a missing language
 * server and a failed request each say why on the status line without touching the document.
 */
class ExtractControllerTest {

    private static final String TEXT = "class Demo {\n    void run() {\n        System.out.println(1 + 2);\n    }\n}\n";
    private static final String EXTRACTED = "class Demo {\n    void run() {\n"
            + "        int newLocal = 1 + 2;\n        System.out.println(newLocal);\n    }\n}\n";
    private static final String VARIABLE_APPLIED = "Extract Variable applied: Extract to local variable (2 edits)";

    @TempDir
    Path project;

    @Test
    void theVariableExtractionIntroducesTheLocalAndPutsTheCaretOnItsName() throws IOException, InterruptedException {
        Path file = write("Variable.java", TEXT);
        Session session = new Session(file, path -> CapabilityResult.ok(List.of(variable(file))));

        session.extract(Extraction.VARIABLE);

        assertEquals(VARIABLE_APPLIED, session.status.get());
        assertEquals(EXTRACTED, session.text());
        assertTrue(session.editor.activeDocument().orElseThrow().dirty());
        assertEquals(2, session.editor.activeDocument().orElseThrow().line(), "the caret is on the declaration");
        assertEquals(12, session.editor.activeDocument().orElseThrow().column(), "on the name newLocal");
        assertTrue(session.editor.undo());
        assertEquals(TEXT, session.text());
        assertFalse(session.editor.undo(), "the extraction is a single undo step");
    }

    @Test
    void aVariableExtractionPrefersTheActionThatReplacesAllOccurrences() throws IOException, InterruptedException {
        Path file = write("Occurrences.java", TEXT);
        CodeAction single = replacing(file, "single", "plain");
        CodeAction everywhere = replacing(file, "single (replace all occurrences)", "everywhere");
        Session session = new Session(file, path -> CapabilityResult.ok(List.of(single, everywhere)));

        session.extract(Extraction.VARIABLE);

        assertTrue(session.status.get().startsWith("Extract Variable applied: single (replace all occurrences)"),
                session.status.get());
        assertTrue(session.text().contains("everywhere"), session.text());
    }

    @Test
    void theMethodExtractionAppliesTheActionOfItsKind() throws IOException, InterruptedException {
        Path file = write("Method.java", TEXT);
        CodeAction method = new CodeAction("Extract to method", "refactor.extract.function", false,
                new WorkspaceEdit(List.of(new DocumentEdit(file, List.of(new TextEdit(0, 0, 0, 0,
                        "void extracted() {}\n")))), List.of()), null);
        Session session = new Session(file, path -> CapabilityResult.ok(List.of(method)));

        session.extract(Extraction.METHOD);

        assertEquals("Extract Method applied: Extract to method (1 edit)", session.status.get());
        assertTrue(session.text().startsWith("void extracted() {}\n"), session.text());
    }

    @Test
    void anActionOfAnotherKindIsNotAnExtractionAndSaysSo() throws IOException, InterruptedException {
        Path file = write("Nothing.java", TEXT);
        CodeAction method = new CodeAction("Extract to method", "refactor.extract.function", false,
                new WorkspaceEdit(List.of(new DocumentEdit(file, List.of(new TextEdit(0, 0, 0, 0, "x")))),
                        List.of()), null);
        Session session = new Session(file, path -> CapabilityResult.ok(List.of(method)));

        session.extract(Extraction.VARIABLE);

        assertEquals("Nothing to extract here", session.status.get());
        assertEquals(TEXT, session.text());
        assertFalse(session.editor.activeDocument().orElseThrow().dirty());
    }

    @Test
    void aMissingLanguageServerSaysWhyAndLeavesTheDocumentAlone() throws IOException, InterruptedException {
        Path file = write("Unavailable.java", TEXT);
        CapabilityRegistry registry = QuickFixFixtures.registryWith(
                CapabilitySelection.<CodeActionCapability>unavailable(CapabilityType.CODE_ACTION,
                        "no language server is configured for this project"));
        Session session = new Session(file, registry);

        session.extract(Extraction.VARIABLE);

        assertEquals("Extract Variable unavailable: no language server is configured for this project",
                session.status.get());
        assertEquals(TEXT, session.text());
    }

    @Test
    void aFailedRequestSaysWhyAndLeavesTheDocumentAlone() throws IOException, InterruptedException {
        Path file = write("Failed.java", TEXT);
        Session session = new Session(file, path -> QuickFixFixtures.failure("timeout",
                "the language server of profile 'jdtls' did not answer within 60000 ms"));

        session.extract(Extraction.CONSTANT);

        assertEquals("Extract Constant failed: timeout: the language server of profile 'jdtls' did not answer "
                + "within 60000 ms", session.status.get());
        assertEquals(TEXT, session.text());
    }

    private static CodeAction variable(Path file) {
        WorkspaceEdit edit = new WorkspaceEdit(List.of(new DocumentEdit(file, List.of(
                new TextEdit(2, 8, 2, 8, "int newLocal = 1 + 2;\n        "),
                new TextEdit(2, 27, 2, 32, "newLocal")))), List.of());
        return new CodeAction("Extract to local variable", "refactor.extract.variable", true, edit, null);
    }

    private static CodeAction replacing(Path file, String title, String replacement) {
        WorkspaceEdit edit = new WorkspaceEdit(List.of(new DocumentEdit(file, List.of(
                new TextEdit(2, 27, 2, 32, replacement)))), List.of());
        return new CodeAction(title, "refactor.extract.variable", false, edit, null);
    }

    private Path write(String name, String contents) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, contents);
        return file.toRealPath();
    }

    /**
     * An editor with one open file and an extract controller whose updates reach a queue the test drains, the way
     * the render thread would take them.
     */
    private final class Session {

        private final BuiltinEditor editor;
        private final ExtractController controller;
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        private final AtomicReference<String> status = new AtomicReference<>("");

        Session(Path file, Function<Path, CapabilityResult<List<CodeAction>>> answer) {
            this(file, QuickFixFixtures.registryWith(QuickFixFixtures.answering(answer)));
        }

        Session(Path file, CapabilityRegistry registry) {
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, 2));
            controller = new ExtractController(registry, editor, deliveries::add, status::set);
        }

        void extract(Extraction extraction) throws InterruptedException {
            controller.run(extraction);
            Objects.requireNonNull(deliveries.poll(10, TimeUnit.SECONDS), "no update reached the render thread").run();
        }

        String text() {
            return editor.activeDocument().orElseThrow().text();
        }
    }
}
