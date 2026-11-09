package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.tamboui.layout.Size;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTypingRouteTest {

    private static final Size SIZE = new Size(80, 24);
    private static final Duration SETTLE = Duration.ofMillis(300);

    @TempDir Path project;

    @Test
    void onlyTheEditorFocusIdsReachTheDocumentWithTyping() throws Exception {
        Path file = project.resolve("vintelli/pom.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project/>\n");
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, 0));
        List<ToolWindow> windows = new ArrayList<>(new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                new ProblemsToolWindow(() -> themes))).toolWindows());
        windows.add(editor);
        VintelliShell shell = new VintelliShell(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes);
        ToolkitTestRunner test = ToolkitTestRunner.runTest(shell::root, SIZE);
        try {
            test.runner().styleEngine(shell.themes().engine());
            test.runner().eventRouter().addGlobalHandler(shell);
            shell.bindQuit(test.runner()::quit);
            Pilot pilot = test.pilot();
            pilot.pause(SETTLE);

            assertTrue(typesInto(editor, pilot, () -> editor.requestFocus(), 'a'),
                    "the editor text area takes typed characters");
            assertTrue(typesInto(editor, pilot, () -> pilot.click("editor-panel"), 'b'),
                    "the editor panel takes typed characters after a click");
            assertTrue(typesInto(editor, pilot,
                    () -> test.runner().focusManager().setFocus("editor-content"), 'd'),
                    "the editor content takes typed characters");
            assertFalse(typesInto(editor, pilot,
                    () -> test.runner().focusManager().setFocus(StatusBarElement.ID), 'e'),
                    "the status bar breadcrumb does not write into the document");
        } finally {
            test.close();
        }
    }

    private static boolean typesInto(BuiltinEditor editor, Pilot pilot, Runnable focus, char key) throws Exception {
        focus.run();
        pilot.pause(SETTLE);
        int before = editor.activeDocument().orElseThrow().text().length();
        pilot.press(key);
        pilot.pause(SETTLE);
        return editor.activeDocument().orElseThrow().text().length() > before;
    }
}
