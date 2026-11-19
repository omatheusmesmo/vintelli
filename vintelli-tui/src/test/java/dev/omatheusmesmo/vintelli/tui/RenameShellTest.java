package dev.omatheusmesmo.vintelli.tui;

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
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.RenameFixtures;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shift+F6 through the assembled shell with the editor focused: the chord opens Rename Symbol on the identifier at
 * the caret, the server's suggestion replaces that text, Enter renames the symbol in the open document, and a position
 * the server rejects says so without opening the input. The screen is read by rendering the shell offline.
 */
class RenameShellTest {

    private static final Size SIZE = new Size(120, 40);
    private static final Rect VIEWPORT = new Rect(0, 0, SIZE.width(), SIZE.height());
    private static final Duration ANSWER_WAIT = Duration.ofSeconds(10);
    private static final String DECLARATION = "class Greeter {\n    void greet() {}\n}\n";
    private static final String RENAMED = "class Greeter {\n    void salute() {}\n}\n";
    private static final int DECLARATION_LINE = 1;
    private static final int CARET_COLUMN = 9;

    @TempDir
    Path project;

    @Test
    void shiftF6OpensRenameSymbolWithTheSuggestedNameInTheInput() throws Exception {
        Harness harness = new Harness(CapabilitySelection.selected(CapabilityType.RENAME,
                RenameFixtures.answering(CapabilityResult.ok("salute"), null)));

        harness.press(KeyCode.F6, KeyModifiers.SHIFT);

        String screen = harness.awaitScreen("> salute");
        assertTrue(screen.contains("Rename Symbol"), screen);
    }

    @Test
    void enterRenamesTheSymbolInTheOpenDocumentAndSaysWhatChanged() throws Exception {
        Path file = declaration();
        WorkspaceEdit edit = new WorkspaceEdit(List.of(new DocumentEdit(file,
                List.of(new TextEdit(DECLARATION_LINE, 9, DECLARATION_LINE, 14, "salute")))), List.of());
        Harness harness = new Harness(CapabilitySelection.selected(CapabilityType.RENAME,
                RenameFixtures.answering(CapabilityResult.ok("greet"), CapabilityResult.ok(edit))));

        harness.press(KeyCode.F6, KeyModifiers.SHIFT);
        harness.typeReplacement("salute");
        harness.press(KeyCode.ENTER);

        harness.awaitText(RENAMED);
        assertTrue(harness.awaitScreen("Renamed to 'salute': 1 occurrence in 1 file").contains("Renamed to 'salute'"));
        assertFalse(harness.screen().contains("Rename Symbol"), "the input closes when the name is confirmed");
    }

    @Test
    void aPositionTheServerRejectsSaysItCannotBeRenamedAndOpensNoInput() throws Exception {
        Harness harness = new Harness(CapabilitySelection.selected(CapabilityType.RENAME,
                RenameFixtures.answering(CapabilityResult.failed(new CapabilityFailure("not-resolvable",
                        "the position does not name a symbol that can be renamed")), null)));

        harness.press(KeyCode.F6, KeyModifiers.SHIFT);

        assertTrue(harness.awaitScreen("Cannot rename this element").contains("Cannot rename this element"));
        assertFalse(harness.screen().contains("Rename Symbol"), harness.screen());
        assertEquals(DECLARATION, harness.editor.activeDocument().orElseThrow().text());
    }

    private Path declaration() throws IOException {
        Path file = project.resolve("Greeter.java");
        Files.writeString(file, DECLARATION);
        return file.toRealPath();
    }

    /**
     * The shell with one open file whose caret sits on the identifier, rendered offline.
     */
    private final class Harness {

        private final BuiltinEditor editor;
        private final VintelliShell shell;

        Harness(CapabilitySelection<RenameCapability> rename) throws Exception {
            Path file = declaration();
            ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file, DECLARATION_LINE));
            editor.goToLine(DECLARATION_LINE, CARET_COLUMN);
            List<ToolWindow> windows = new ArrayList<>(new ToolWindowRegistry(List.of(
                    new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                    new ProblemsToolWindow(() -> themes))).toolWindows());
            windows.add(editor);
            List<ToolWindow> toolWindows = new ToolWindowRegistry(windows).toolWindows();
            ShellConfig config = new ShellConfig(toolWindows, new ShellState(),
                    () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(),
                    RenameFixtures.registryWith(rename),
                    List.of(), false, toolWindows, null, null, IdeaStatusBadge.absent(), null, false);
            shell = new VintelliShell(config);
            shell.useUiWorkQueue(new UiWorkQueue());
            render();
            editor.requestFocus();
            render();
        }

        void press(KeyCode key, KeyModifiers modifiers) {
            shell.handle(KeyEvent.ofKey(key, modifiers));
            render();
        }

        void press(KeyCode key) {
            shell.handle(KeyEvent.ofKey(key));
            render();
        }

        /** Replaces the suggested text in the input, as a user does: Backspace over it, then type the name. */
        void typeReplacement(String name) {
            for (int index = 0; index < 20; index++) {
                press(KeyCode.BACKSPACE);
            }
            for (char character : name.toCharArray()) {
                shell.handle(KeyEvent.ofChar(character));
                render();
            }
        }

        String screen() {
            return text(render());
        }

        String awaitScreen(String expected) throws InterruptedException {
            long deadline = System.nanoTime() + ANSWER_WAIT.toNanos();
            String screen = screen();
            while (!screen.contains(expected) && System.nanoTime() < deadline) {
                Thread.sleep(20);
                screen = screen();
            }
            return screen;
        }

        void awaitText(String expected) throws InterruptedException {
            long deadline = System.nanoTime() + ANSWER_WAIT.toNanos();
            while (!expected.equals(editor.activeDocument().orElseThrow().text()) && System.nanoTime() < deadline) {
                render();
                Thread.sleep(20);
            }
            assertEquals(expected, editor.activeDocument().orElseThrow().text());
        }

        private Buffer render() {
            Buffer buffer = Buffer.empty(VIEWPORT);
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(shell.themes().engine());
            Element root = shell.root();
            root.render(Frame.forTesting(buffer), VIEWPORT, context);
            context.registerElement(root, VIEWPORT);
            return buffer;
        }

        private String text(Buffer buffer) {
            StringBuilder text = new StringBuilder();
            for (int y = 0; y < buffer.height(); y++) {
                for (int x = 0; x < buffer.width(); x++) {
                    text.append(buffer.get(x, y).symbol());
                }
                text.append('\n');
            }
            return text.toString();
        }
    }
}
