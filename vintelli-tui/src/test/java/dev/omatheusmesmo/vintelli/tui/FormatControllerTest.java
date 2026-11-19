package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.FormattingOptions;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.FormatController;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reformat and organize imports of the active document, driven headlessly: the server answers the text the editor
 * holds, the answer lands as one undoable step on the render thread, the caret stays on its code line, and every
 * outcome that does not change the text says why on the status line.
 */
class FormatControllerTest {

    private static final String TWO_SPACES = "class Fake {\n  void run() {\n  }\n}\n";
    private static final String FOUR_SPACES = "class Fake {\n    void run() {\n    }\n}\n";
    private static final List<TextEdit> INDENT_FIXES = List.of(new TextEdit(1, 0, 1, 2, "    "),
            new TextEdit(2, 0, 2, 2, "    "));
    private static final String IMPORTS = "import java.util.List;\nimport java.util.LinkedList;\n\n"
            + "class Fake {\n    List<String> names;\n}\n";
    private static final String ORGANIZED = "import java.util.List;\n\nclass Fake {\n    List<String> names;\n}\n";
    private static final String UNCHANGED = "; nothing was changed";

    @TempDir
    Path project;

    @Test
    void reformatAppliesTheServersEditsAsOneUndoStepAndSendsTheIndentOfTheDocument() throws Exception {
        Path file = write("Indent.java", TWO_SPACES);
        Provider provider = new Provider();
        provider.documentAnswer = CapabilityResult.ok(INDENT_FIXES);
        Session session = new Session(file, 1, provider);

        assertEquals("Reformatting...", session.controller.reformat());
        session.awaitOutcome();

        assertEquals("Reformatted", session.status.get());
        assertEquals(FOUR_SPACES, session.text());
        assertTrue(session.editor.activeDocument().orElseThrow().dirty());
        assertEquals(List.of("document 2 true"), provider.requests(), "two spaces is the indent of the file");
        assertTrue(session.editor.undo());
        assertEquals(TWO_SPACES, session.text());
        assertFalse(session.editor.undo(), "the reformat is a single undo step");
    }

    @Test
    void aSelectionIsFormattedAsARangeAndTheRestOfTheDocumentStaysAsItIs() throws Exception {
        Path file = write("Range.java", TWO_SPACES);
        Provider provider = new Provider();
        provider.rangeAnswer = CapabilityResult.ok(List.of(INDENT_FIXES.getFirst()));
        Session session = new Session(file, 1, provider);
        session.editor.handleKey(KeyEvent.ofKey(KeyCode.DOWN, KeyModifiers.SHIFT));

        session.controller.reformat();
        session.awaitOutcome();

        assertEquals("Reformatted", session.status.get());
        assertEquals("class Fake {\n    void run() {\n  }\n}\n", session.text(), "the last line keeps its indent");
        assertEquals(List.of("range 1,0,2,0 2 true"), provider.requests());
    }

    @Test
    void aDocumentTheServerLeavesAloneSaysSoAndIsNotDirty() throws Exception {
        Path file = write("Formatted.java", FOUR_SPACES);
        Session session = new Session(file, 1, new Provider());

        session.controller.reformat();
        session.awaitOutcome();

        assertEquals("Already formatted", session.status.get());
        assertEquals(FOUR_SPACES, session.text());
        assertFalse(session.editor.activeDocument().orElseThrow().dirty());
    }

    @Test
    void noLanguageServerSaysWhyAndLeavesTheDocumentAsItIs() throws Exception {
        Path file = write("Missing.java", TWO_SPACES);
        Session session = new Session(file, 1, CapabilitySelection.unavailable(CapabilityType.CODE_ACTION,
                "the command 'jdtls' was not found; is the language server installed?"));

        session.controller.reformat();
        session.awaitOutcome();

        assertEquals("Reformat did not complete: the command 'jdtls' was not found; is the language server installed?"
                + UNCHANGED, session.status.get());
        assertEquals(TWO_SPACES, session.text());
    }

    @Test
    void aServerThatDoesNotAnswerSaysWhyAndLeavesTheDocumentAsItIs() throws Exception {
        Path file = write("Timeout.java", TWO_SPACES);
        Provider provider = new Provider();
        provider.documentAnswer = CapabilityResult.failed(new CapabilityFailure("timeout",
                "the language server of profile 'jdtls' did not answer 'formatting' within 60000 ms"));
        Session session = new Session(file, 1, provider);

        session.controller.reformat();
        session.awaitOutcome();

        assertEquals("Reformat did not complete: the language server of profile 'jdtls' did not answer "
                + "'formatting' within 60000 ms" + UNCHANGED, session.status.get());
        assertEquals(TWO_SPACES, session.text());
    }

    @Test
    void theAnswerIsRejectedWhenTheTextChangedWhileTheServerWorked() throws Exception {
        Path file = write("Typed.java", TWO_SPACES);
        Provider provider = new Provider();
        provider.documentAnswer = CapabilityResult.ok(INDENT_FIXES);
        Session session = new Session(file, 1, provider);

        session.controller.reformat();
        session.editor.handleKey(KeyEvent.ofChar('x'));
        session.awaitOutcome();

        assertEquals("Reformat not applied: the text changed while the server worked on it" + UNCHANGED,
                session.status.get());
        assertEquals("class Fake {\nx  void run() {\n  }\n}\n", session.text(), "only the typed character is there");
    }

    @Test
    void organizeImportsRemovesTheUnusedImportAndKeepsTheCaretOnItsCodeLine() throws Exception {
        Path file = write("Imports.java", IMPORTS);
        Provider provider = new Provider();
        provider.importsAnswer = CapabilityResult.ok(List.of(organizeImports(file)));
        Session session = new Session(file, 4, provider);

        assertEquals("Optimizing imports...", session.controller.optimizeImports());
        session.awaitOutcome();

        assertEquals("Imports optimized", session.status.get());
        assertEquals(ORGANIZED, session.text());
        BuiltinEditor.DocumentView view = session.editor.activeDocument().orElseThrow();
        assertTrue(view.text().split("\n", -1)[view.line()].contains("names"),
                "the caret stays on the field line, which moved up one row");
        assertEquals(List.of("imports"), provider.requests());
    }

    @Test
    void organizeImportsWithNothingToOrganizeSaysSo() throws Exception {
        Path file = write("Clean.java", ORGANIZED);
        Session session = new Session(file, 0, new Provider());

        session.controller.optimizeImports();
        session.awaitOutcome();

        assertEquals("Imports already optimized", session.status.get());
        assertEquals(ORGANIZED, session.text());
        assertFalse(session.editor.activeDocument().orElseThrow().dirty());
    }

    private static CodeAction organizeImports(Path file) {
        WorkspaceEdit edit = new WorkspaceEdit(List.of(new DocumentEdit(file, List.of(new TextEdit(1, 0, 2, 0, "")))),
                List.of());
        return new CodeAction("Organize imports", "source.organizeImports", false, edit, null);
    }

    private Path write(String name, String contents) throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, contents);
        return file.toRealPath();
    }

    /**
     * A code action provider with the answers a test sets, which records the requests it receives.
     */
    private static final class Provider implements CodeActionCapability {

        private CapabilityResult<List<TextEdit>> documentAnswer = CapabilityResult.ok(List.of());
        private CapabilityResult<List<TextEdit>> rangeAnswer = CapabilityResult.ok(List.of());
        private CapabilityResult<List<CodeAction>> importsAnswer = CapabilityResult.ok(List.of());
        private final List<String> requests = new CopyOnWriteArrayList<>();

        List<String> requests() {
            return List.copyOf(requests);
        }

        @Override
        public CapabilityResult<List<CodeAction>> codeActions(Path file, SourceRange range,
                List<DiagnosticsCapability.Diagnostic> diagnostics) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public CapabilityResult<List<TextEdit>> formatDocument(Path file, String content, FormattingOptions options) {
            requests.add("document " + options.tabSize() + " " + options.insertSpaces());
            return documentAnswer;
        }

        @Override
        public CapabilityResult<List<TextEdit>> formatRange(Path file, String content, SourceRange range,
                FormattingOptions options) {
            requests.add("range " + range.startLine() + "," + range.startColumn() + "," + range.endLine() + ","
                    + range.endColumn() + " " + options.tabSize() + " " + options.insertSpaces());
            return rangeAnswer;
        }

        @Override
        public CapabilityResult<List<CodeAction>> organizeImports(Path file, String content) {
            requests.add("imports");
            return importsAnswer;
        }

        @Override
        public String identifier() {
            return "fixture-ls";
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

    /**
     * An editor with one open file and a controller whose updates go to a queue the test drains, answered by the
     * given code action selection.
     */
    private final class Session {

        private final BuiltinEditor editor;
        private final FormatController controller;
        private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        private final AtomicReference<String> status = new AtomicReference<>("");

        Session(Path file, int row, Provider provider) {
            this(file, row, CapabilitySelection.selected(CapabilityType.CODE_ACTION, provider));
        }

        Session(Path file, int row, CapabilitySelection<CodeActionCapability> selection) {
            ShellThemes themes = new ShellThemes("dark", (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, row));
            CapabilityRegistry registry = QuickFixFixtures.registryWith(selection);
            controller = new FormatController(registry, editor, deliveries::add, status::set);
        }

        String text() {
            return editor.activeDocument().orElseThrow().text();
        }

        void awaitOutcome() throws InterruptedException {
            Objects.requireNonNull(deliveries.poll(10, TimeUnit.SECONDS), "no update reached the render thread").run();
        }
    }
}
