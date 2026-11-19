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
import java.util.List;

/**
 * Back and Forward through the assembled shell: Go to Definition into another file, Ctrl+Alt+Left returns to the
 * caret it left, Ctrl+Alt+Right goes again, and a new jump after Back drops the forward place. The editor API covers
 * the cases that need a closed tab or a deleted file.
 */
class NavigationShellTest {

    private static final Size SIZE = new Size(120, 40);
    private static final Rect VIEWPORT = new Rect(0, 0, SIZE.width(), SIZE.height());
    private static final KeyModifiers CTRL_ALT = KeyModifiers.of(true, true, false);
    private static final String MAIN = "class Main {\n    void run() {\n    }\n}\n";
    private static final String ABSTRACT_CONTROLLER = """
            package com.example.web;

            class AbstractController {
                void abcoRun() {
                }
            }
            """;
    private static final int DEFINITION_LINE = 2;
    private static final int CALL_LINE = 1;
    private static final int CALL_COLUMN = 4;

    @TempDir
    Path project;

    @Test
    void goToDefinitionThenBackReturnsTheCaretAndForwardGoesAgain() throws Exception {
        Harness harness = new Harness();
        harness.placeCaretInMain();

        harness.press('b', KeyModifiers.CTRL);
        assertEquals(harness.controllerFile, harness.activeFile());
        assertEquals(DEFINITION_LINE, harness.activeLine());

        harness.press(KeyEvent.builder().code(KeyCode.LEFT).modifiers(CTRL_ALT).build());
        assertEquals(harness.mainFile, harness.activeFile());
        assertEquals(CALL_LINE, harness.activeLine());
        assertEquals(CALL_COLUMN, harness.activeColumn());

        harness.press(KeyEvent.builder().code(KeyCode.RIGHT).modifiers(CTRL_ALT).build());
        assertEquals(harness.controllerFile, harness.activeFile());
        assertEquals(DEFINITION_LINE, harness.activeLine());
    }

    @Test
    void aNewJumpAfterBackDropsTheForwardPlace() throws Exception {
        Harness harness = new Harness();
        harness.placeCaretInMain();
        harness.press('b', KeyModifiers.CTRL);
        harness.press(KeyEvent.builder().code(KeyCode.LEFT).modifiers(CTRL_ALT).build());

        harness.press('b', KeyModifiers.CTRL);
        harness.press(KeyEvent.builder().code(KeyCode.RIGHT).modifiers(CTRL_ALT).build());

        assertEquals(harness.controllerFile, harness.activeFile());
        assertEquals("No place to go forward to", harness.editor.status());
    }

    @Test
    void backReopensAFileThatWasClosed() throws Exception {
        Harness harness = new Harness();
        harness.editor.open(new OpenRequest(harness.mainFile, CALL_LINE));
        harness.editor.open(new OpenRequest(harness.controllerFile, DEFINITION_LINE));
        harness.editor.open(new OpenRequest(harness.mainFile, CALL_LINE));
        assertEquals(BuiltinEditor.CloseResult.CLOSED, harness.editor.closeActive());
        assertEquals(BuiltinEditor.CloseResult.CLOSED, harness.editor.closeActive());
        assertTrue(harness.editor.tabs().isEmpty());

        assertTrue(harness.editor.navigateBack());
        assertEquals(harness.controllerFile, harness.activeFile());
        assertEquals(DEFINITION_LINE, harness.activeLine());
    }

    @Test
    void backSkipsAPlaceWhoseFileWasDeleted() throws Exception {
        Harness harness = new Harness();
        harness.editor.open(new OpenRequest(harness.controllerFile, DEFINITION_LINE));
        harness.editor.open(new OpenRequest(harness.mainFile, CALL_LINE));
        Files.delete(harness.controllerFile);

        assertTrue(harness.editor.navigateBack());
        assertEquals(harness.mainFile, harness.activeFile());
        assertEquals(0, harness.activeLine());
        assertFalse(harness.editor.navigateBack());
        assertEquals("No place to go back to", harness.editor.status());
    }

    private final class Harness {

        private final BuiltinEditor editor;
        private final VintelliShell shell;
        private final Path mainFile;
        private final Path controllerFile;

        Harness() throws Exception {
            Path main = project.resolve("Main.java");
            Files.writeString(main, MAIN);
            Path controller = project.resolve("AbstractController.java");
            Files.writeString(controller, ABSTRACT_CONTROLLER);
            mainFile = main.toRealPath();
            controllerFile = controller.toRealPath();
            ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            assertFalse(editor.open(new OpenRequest(mainFile, 0)).isFailure());
            shell = new VintelliShell(ShellConfig.of(List.of(editor), new ShellState(), themes)
                    .withNavigationProviders(List.of(new FixedDefinition(controllerFile))));
            render();
            editor.requestFocus();
            render();
        }

        void placeCaretInMain() {
            editor.goToLine(CALL_LINE + 1, CALL_COLUMN + 1);
        }

        Path activeFile() {
            return Path.of(editor.activeDocument().orElseThrow().file());
        }

        int activeLine() {
            return editor.activeDocument().orElseThrow().line();
        }

        int activeColumn() {
            return editor.activeDocument().orElseThrow().column();
        }

        void press(char key, KeyModifiers modifiers) {
            press(KeyEvent.ofChar(key, modifiers));
        }

        void press(KeyEvent key) {
            shell.handle(key);
            render();
        }

        private void render() {
            Buffer buffer = Buffer.empty(VIEWPORT);
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(shell.themes().engine());
            Element root = shell.root();
            root.render(Frame.forTesting(buffer), VIEWPORT, context);
            context.registerElement(root, VIEWPORT);
        }
    }

    /** Answers every definition query with one location in the controller, and nothing else. */
    private static final class FixedDefinition implements NavigationCapability {

        private final Path controller;

        FixedDefinition(Path controller) {
            this.controller = controller;
        }

        @Override
        public CapabilityResult<List<Location>> definition(SourcePosition position) {
            return CapabilityResult.ok(List.of(new Location(controller, DEFINITION_LINE, 6)));
        }

        @Override
        public CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "not under test"));
        }

        @Override
        public CapabilityResult<List<WorkspaceSymbol>> workspaceSymbols(String query) {
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
