package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemsToolWindowTest {

    @TempDir
    Path project;

    @Test
    void asynchronouslyRendersGroupedDiagnosticAndClickNavigatesToItsPosition() throws Exception {
        Path file = Files.writeString(project.resolve("Broken.java"), String.join("\n",
                IntStream.range(0, 12).mapToObj(index -> "line " + index).toList()));
        Path otherFile = Files.writeString(project.resolve("Other.java"), "other\nissue");
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(file, 0));
        editor.open(new EditorBridge.OpenRequest(otherFile, 0));
        List<Diagnostic> diagnostics = IntStream.range(0, 6)
                .mapToObj(index -> new Diagnostic(file, index + 3, 2, index + 3, 8,
                        index % 2 == 0 ? DiagnosticsCapability.Severity.ERROR : DiagnosticsCapability.Severity.WARNING,
                        "jdtls", "problem " + index))
                .toList();
        diagnostics = Stream.concat(diagnostics.stream(),
                Stream.of(new Diagnostic(otherFile, 1, 0, 1, 5,
                        DiagnosticsCapability.Severity.WARNING, "jdtls", "other issue"))).toList();
        DiagnosticsCapability provider = new TestDiagnostics(diagnostics);
        ProblemsToolWindow window = new ProblemsToolWindow(new TestRegistry(provider), editor, () -> themes);
        VintelliShell shell = new VintelliShell(List.of(window, editor), new ShellState(), themes,
                new TestRegistry(provider));

        window.refresh();
        awaitProblems(window);
        Rect area = new Rect(0, 0, 90, 6);
        Buffer buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        String rendered = rows(buffer, area);

        assertTrue(rendered.contains("Broken.java"), rendered);
        assertTrue(rendered.contains("ERROR  L4  jdtls  problem 0"), rendered);
        assertTrue(rendered.contains("jdtls over stdio"), rendered);
        assertTrue(window.handleKey(KeyEvent.ofChar('e')));
        buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        assertTrue(rows(buffer, area).contains("problem 0"));
        assertFalse(rows(buffer, area).contains("problem 1"));
        assertTrue(window.handleKey(KeyEvent.ofChar('w')));
        buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        assertTrue(rows(buffer, area).contains("problem 1"));
        assertFalse(rows(buffer, area).contains("problem 0"));
        assertTrue(window.handleKey(KeyEvent.ofChar('a')));
        assertTrue(window.handleKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertTrue(window.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(4, editor.activeDocument().orElseThrow().line());
        assertEquals("Opened Broken.java", shell.status());
        assertTrue(window.handleMouse(MouseEvent.scrollDown(4, 2)));
        buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        assertTrue(rows(buffer, area).contains("problem 3"), rows(buffer, area));
        assertEquals(KeyActions.PROBLEMS_NAVIGATE,
                window.actionForMouse(MouseEvent.press(MouseButton.LEFT, 4, 2)));
        assertTrue(window.performAction(KeyActions.PROBLEMS_NAVIGATE));
        assertEquals(6, editor.activeDocument().orElseThrow().line());
        assertEquals("Opened Broken.java", shell.status());
        int selected = window.selectedIndex();
        assertNull(window.actionForMouse(MouseEvent.press(MouseButton.RIGHT, 4, 3)));
        assertFalse(window.handleMouse(MouseEvent.press(MouseButton.RIGHT, 4, 3)));
        assertEquals(selected, window.selectedIndex());
        assertEquals(6, editor.activeDocument().orElseThrow().line());
        assertTrue(window.handleMouse(MouseEvent.scrollDown(4, 2)));
        buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        assertTrue(rows(buffer, area).contains("Other.java"), rows(buffer, area));
        assertTrue(rows(buffer, area).contains("other issue"), rows(buffer, area));
        window.close();
    }

    @Test
    void showsEmptyAndUnavailableStatesExplicitly() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        Rect area = new Rect(0, 0, 90, 6);
        ProblemsToolWindow empty = new ProblemsToolWindow(
                new TestRegistry(new TestDiagnostics(List.of())), editor, () -> themes);
        empty.refresh();
        assertTrue(awaitRender(empty, area, "Open files in the editor"));

        ProblemsToolWindow unavailable = new ProblemsToolWindow(new TestRegistry(null), editor, () -> themes);
        unavailable.refresh();
        assertTrue(awaitRender(unavailable, area, "not available in test"));
        empty.close();
        unavailable.close();
    }

    @Test
    void showsProviderFailureAsAnErrorState() throws Exception {
        Path file = Files.writeString(project.resolve("Broken.java"), "class Broken {}\n");
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(file, 0));
        ProblemsToolWindow window = new ProblemsToolWindow(new TestRegistry(new FailedDiagnostics()), editor,
                () -> themes);

        window.refresh();

        assertTrue(awaitRender(window, new Rect(0, 0, 90, 6), "language server unavailable"));
        assertTrue(render(window, new Rect(0, 0, 90, 6)).contains("Error"));
        window.close();
    }

    private static void awaitProblems(ProblemsToolWindow window) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (window.problems().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(7, window.problems().size());
    }

    private static String rows(Buffer buffer, Rect area) {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < area.height(); y++) {
            for (int x = 0; x < area.width(); x++) text.append(buffer.get(x, y).symbol());
            text.append('\n');
        }
        return text.toString();
    }

    private static boolean awaitRender(ProblemsToolWindow window, Rect area, String text) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (render(window, area).contains(text)) return true;
            Thread.sleep(5);
        }
        return false;
    }

    private static String render(ProblemsToolWindow window, Rect area) {
        Buffer buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        return rows(buffer, area);
    }

    private static final class TestDiagnostics implements DiagnosticsCapability {
        private final List<Diagnostic> diagnostics;
        private TestDiagnostics(List<Diagnostic> diagnostics) { this.diagnostics = diagnostics; }
        @Override public String identifier() { return "jdtls"; }
        @Override public String provenance() { return "jdtls over stdio"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public CapabilityResult<List<Diagnostic>> diagnostics(Path file) {
            return CapabilityResult.ok(diagnostics.stream().filter(problem -> problem.filePath().equals(file)).toList());
        }
    }

    private static final class FailedDiagnostics implements DiagnosticsCapability {
        @Override public String identifier() { return "jdtls"; }
        @Override public String provenance() { return "jdtls over stdio"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public CapabilityResult<List<Diagnostic>> diagnostics(Path file) {
            return CapabilityResult.failed(new CapabilityFailure("not-ready", "language server unavailable"));
        }
    }

    private static final class TestRegistry implements CapabilityRegistry {
        private final DiagnosticsCapability diagnostics;
        private TestRegistry(DiagnosticsCapability diagnostics) { this.diagnostics = diagnostics; }
        @Override public CapabilitySelection<RunCapability> run() { return unavailable(CapabilityType.RUN); }
        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() {
            return diagnostics == null ? unavailable(CapabilityType.DIAGNOSTICS)
                    : CapabilitySelection.selected(CapabilityType.DIAGNOSTICS, diagnostics);
        }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() { return List.of(); }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType capability) {
            return CapabilitySelection.unavailable(capability, "not available in test");
        }
    }
}
