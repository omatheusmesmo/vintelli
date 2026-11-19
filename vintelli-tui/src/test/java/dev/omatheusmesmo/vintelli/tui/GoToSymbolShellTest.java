package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentSymbol;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.SourcePosition;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.WorkspaceSymbol;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Go to Class and Go to Symbol through the assembled shell: the chord opens the overlay, the answer of the navigation
 * provider is drawn with its kind and container, Enter opens the selected location in the editor, and a symbol outside
 * the project files stays listed without opening. The provider is a fixed answer; the LSP side is covered by the LSP
 * tests.
 */
class GoToSymbolShellTest {

    private static final int CLASS = 5;
    private static final int METHOD = 6;
    private static final Size SIZE = new Size(120, 40);
    private static final Rect VIEWPORT = new Rect(0, 0, SIZE.width(), SIZE.height());
    private static final Duration ANSWER_WAIT = Duration.ofSeconds(10);
    private static final String MAIN = "class Main {\n    void run() {\n    }\n}\n";
    private static final String ABSTRACT_CONTROLLER = """
            package com.example.web;

            class AbstractController {
                void abcoRun() {
                }
            }
            """;

    @TempDir
    Path project;

    @Test
    void ctrlNListsOnlyTypesWithTheirKindAndContainer() throws Exception {
        Harness harness = new Harness();

        harness.press('n', KeyModifiers.CTRL);
        harness.type("AbCo");

        String screen = harness.awaitScreen("AbstractController");
        assertTrue(screen.contains("Go to Class"), screen);
        assertTrue(screen.contains("class") && screen.contains("com.example.web"), screen);
        assertFalse(screen.contains("abcoRun"), "Go to Class lists types only: " + screen);
    }

    @Test
    void ctrlAltShiftNListsEverySymbolKind() throws Exception {
        Harness harness = new Harness();

        harness.press(KeyEvent.builder().code(KeyCode.CHAR).modifiers(KeyModifiers.of(true, true, true))
                .character('N').keyCodePoint('n').shiftedKeyCodePoint('N').baseLayoutKeyCodePoint('n').build());
        harness.type("AbCo");

        String screen = harness.awaitScreen("abcoRun");
        assertTrue(screen.contains("Go to Symbol"), screen);
        assertTrue(screen.contains("method"), screen);
    }

    @Test
    void enterOpensTheSelectedSymbolAtItsLine() throws Exception {
        Harness harness = new Harness();

        harness.press('n', KeyModifiers.CTRL);
        harness.type("AbCo");
        harness.awaitScreen("AbstractController");
        harness.press(KeyCode.ENTER);

        assertEquals(harness.controller.toRealPath(), Path.of(harness.editor.activeDocument().orElseThrow().file()));
        assertEquals(2, harness.editor.activeDocument().orElseThrow().line());
        assertFalse(harness.screen().contains("Go to Class"), "Enter closes the overlay");
    }

    @Test
    void aSymbolOutsideTheProjectFilesIsListedAndEnterDoesNotOpenIt() throws Exception {
        Harness harness = new Harness();

        harness.press('n', KeyModifiers.CTRL);
        harness.type("AbCo");
        harness.awaitScreen("ArrayList");
        harness.press(KeyCode.DOWN);
        harness.press(KeyCode.ENTER);

        String screen = harness.screen();
        assertTrue(screen.contains("(not openable)"), screen);
        assertTrue(screen.contains("Go to Class"), "the overlay stays open: " + screen);
        assertEquals(harness.main.toRealPath(), Path.of(harness.editor.activeDocument().orElseThrow().file()));
    }

    private final class Harness {

        private final BuiltinEditor editor;
        private final VintelliShell shell;
        private final Path main;
        private final Path controller;

        Harness() throws Exception {
            main = project.resolve("Main.java");
            Files.writeString(main, MAIN);
            controller = project.resolve("AbstractController.java");
            Files.writeString(controller, ABSTRACT_CONTROLLER);
            ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            assertFalse(editor.open(new OpenRequest(main.toRealPath(), 0)).isFailure());
            shell = new VintelliShell(ShellConfig.of(List.of(editor), new ShellState(), themes)
                    .withNavigationProviders(List.of(new FixedNavigation(controller.toRealPath()))));
            render();
            editor.requestFocus();
            render();
        }

        void press(char key, KeyModifiers modifiers) {
            press(KeyEvent.ofChar(key, modifiers));
        }

        void press(KeyCode key) {
            shell.handle(KeyEvent.ofKey(key));
            render();
        }

        void press(KeyEvent key) {
            shell.handle(key);
            render();
        }

        void type(String text) {
            text.codePoints().forEach(codePoint -> {
                shell.handle(KeyEvent.ofChar(codePoint));
                render();
            });
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

    /** Answers every query with one class, one method and one library type, the way a server answers a camel hump. */
    private static final class FixedNavigation implements NavigationCapability {

        private final Path controller;

        FixedNavigation(Path controller) {
            this.controller = controller;
        }

        @Override
        public CapabilityResult<List<WorkspaceSymbol>> workspaceSymbols(String query) {
            return CapabilityResult.ok(List.of(
                    new WorkspaceSymbol("AbstractController", CLASS, "com.example.web",
                            Optional.of(new Location(controller, 2, 6))),
                    new WorkspaceSymbol("abcoRun", METHOD, "AbstractController", Optional.of(new Location(controller, 3, 4))),
                    new WorkspaceSymbol("ArrayList", CLASS, "java.util", Optional.empty())));
        }

        @Override
        public CapabilityResult<List<Location>> definition(SourcePosition position) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "not under test"));
        }

        @Override
        public CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "not under test"));
        }

        @Override
        public CapabilityResult<List<DocumentSymbol>> documentSymbols(Path file) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "not under test"));
        }

        @Override
        public String identifier() {
            return "fixed";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "fixed answers";
        }
    }
}
