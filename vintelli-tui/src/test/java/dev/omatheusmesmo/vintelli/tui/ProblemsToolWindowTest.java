package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
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
import dev.tamboui.style.Style;
import dev.tamboui.tui.event.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(window, editor), new ShellState(), themes)
                .withCapabilityRegistry(new TestRegistry(provider)));

        window.refresh();
        awaitProblems(window);
        Rect area = new Rect(0, 0, 90, 6);
        Buffer buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        String rendered = rows(buffer, area);

        assertTrue(rendered.contains("Broken.java"), rendered);
        assertTrue(rendered.contains("> x ERROR  L4:C3  jdtls  problem 0"), rendered);
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
        assertEquals(5, editor.activeDocument().orElseThrow().line(),
                "filtering to warnings preserves that selection when showing all diagnostics, then Down selects the next row");
        assertTrue(window.handleMouse(MouseEvent.scrollDown(4, 2)));
        buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        assertTrue(rows(buffer, area).contains("problem 3"), rows(buffer, area));
        int problemThreeRow = rowContaining(rows(buffer, area), "problem 3");
        assertNull(window.actionForMouse(MouseEvent.press(MouseButton.LEFT, 4, problemThreeRow)),
                "a single click only selects the problem");
        assertTrue(window.handleMouse(MouseEvent.press(MouseButton.LEFT, 4, problemThreeRow)));
        assertEquals(5, editor.activeDocument().orElseThrow().line(), "a single click does not jump to the problem");
        assertEquals(KeyActions.PROBLEMS_NAVIGATE,
                window.actionForMouse(MouseEvent.press(MouseButton.LEFT, 4, problemThreeRow)),
                "a second click on the same problem within the double click window navigates to it");
        assertTrue(window.performAction(KeyActions.PROBLEMS_NAVIGATE));
        assertEquals(6, editor.activeDocument().orElseThrow().line());
        int selected = window.selectedIndex();
        assertNull(window.actionForMouse(MouseEvent.press(MouseButton.RIGHT, 4, 3)));
        assertFalse(window.handleMouse(MouseEvent.press(MouseButton.RIGHT, 4, 3)));
        assertNull(window.actionForMouse(MouseEvent.press(MouseButton.LEFT, area.x() + area.width(), 3)),
                "clicks outside the rendered content are not problem rows");
        assertNull(window.actionForMouse(MouseEvent.press(MouseButton.LEFT, 4, 0)), "title row clicks are not problem rows");
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
        assertTrue(awaitRender(unavailable, area, "Diagnostics unavailable: not available in test. Press R to retry."));
        assertEquals(1, occurrences(render(unavailable, area), "not available in test"));
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

    @Test
    void rowsShowSeverityLineColumnAndHeaderCounts() throws Exception {
        Path file = Files.writeString(project.resolve("Broken.java"), "class Broken {}\n");
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(file, 0));
        List<Diagnostic> diagnostics = List.of(
                new Diagnostic(file, 3, 2, 3, 8, Severity.ERROR, "jdtls", "cannot find symbol"),
                new Diagnostic(file, 6, 0, 6, 4, Severity.WARNING, "jdtls", "unused import"),
                new Diagnostic(file, 7, 4, 7, 9, Severity.HINT, "jdtls", "consider final"));
        ProblemsToolWindow window = new ProblemsToolWindow(new TestRegistry(new TestDiagnostics(diagnostics)),
                editor, () -> themes);
        window.refresh();
        assertTrue(awaitDiagnostics(window, diagnostics.size()));
        Rect area = new Rect(0, 0, 100, 10);
        Buffer buffer = Buffer.empty(area);
        window.render(Frame.forTesting(buffer), area);
        String rendered = rows(buffer, area);

        assertTrue(rendered.contains("1 error, 1 warning, 1 other"), rendered);
        assertTrue(rendered.contains("L4:C3") && rendered.contains("cannot find symbol"), rendered);
        assertTrue(rendered.lines().anyMatch(line -> line.contains("L7:C1") && line.contains("unused import")), rendered);
        assertTrue(rendered.lines().anyMatch(line -> line.contains("L8:C5") && line.contains("consider final")), rendered);

        int errorRow = rowContaining(rendered, "ERROR");
        int errorColumn = rendered.lines().toList().get(errorRow).indexOf("ERROR");
        Style errorStyle = buffer.get(errorColumn, errorRow).style();
        assertEquals(themes.resolver("problem-error").toStyle().fg(), errorStyle.fg());
        assertNotEquals(themes.resolver("tool-window").toStyle().fg(), errorStyle.fg());
        window.close();
    }

    @Test
    void longMessagesWrapUnderTheMessageColumnInsteadOfBeingCut() throws Exception {
        String message = "incompatible types: String cannot be converted to int in the overloaded call";
        Path file = Files.writeString(project.resolve("Broken.java"), "class Broken {}\n");
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(file, 0));
        List<Diagnostic> diagnostics = List.of(new Diagnostic(file, 0, 0, 0, 1, Severity.ERROR, "jdtls", message));
        ProblemsToolWindow window = new ProblemsToolWindow(new TestRegistry(new TestDiagnostics(diagnostics)),
                editor, () -> themes);
        window.refresh();
        assertTrue(awaitDiagnostics(window, 1));
        Rect area = new Rect(0, 0, 60, 10);

        String rendered = render(window, area);

        assertTrue(rendered.replaceAll("\\s+", " ").contains(message), rendered);
        List<String> lines = rendered.lines().toList();
        int first = rowContaining(rendered, "incompatible");
        int messageColumn = lines.get(first).indexOf("incompatible");
        String continuation = lines.get(first + 1);
        assertEquals(messageColumn, continuation.length() - continuation.stripLeading().length(), rendered);
        window.close();
    }

    @Test
    void unavailableStateNamesTheReasonOnceAndTheHeaderOnlyTheStatus() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        ProblemsToolWindow window = new ProblemsToolWindow(new TestRegistry(null), editor, () -> themes);
        Rect area = new Rect(0, 0, 90, 6);
        window.refresh();
        assertTrue(awaitRender(window, area, "Diagnostics unavailable"));

        String rendered = render(window, area);

        assertEquals(1, occurrences(rendered, "not available in test"), rendered);
        assertTrue(rendered.contains("Problems  Unavailable"), rendered);
        assertEquals(0, occurrences(rendered, "Problems unavailable"), rendered);
        window.close();
    }

    private static boolean awaitDiagnostics(ProblemsToolWindow window, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (window.problems().size() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return window.problems().size() == expected;
    }

    private static int rowContaining(String screen, String fragment) {
        List<String> rows = screen.lines().toList();
        for (int row = 0; row < rows.size(); row++) {
            if (rows.get(row).contains(fragment)) return row;
        }
        return -1;
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + fragment.length())) {
            count++;
        }
        return count;
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
            return CapabilityResult.ok(diagnostics.stream().filter(problem -> sameFile(problem.filePath(), file)).toList());
        }

        private static boolean sameFile(Path left, Path right) {
            try {
                return Files.isSameFile(left, right);
            } catch (IOException unavailable) {
                return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
            }
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
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() {
            return diagnostics == null
                    ? List.of(CapabilityStatus.unavailable(CapabilityType.DIAGNOSTICS, "not available in test"))
                    : List.of(CapabilityStatus.available(CapabilityType.DIAGNOSTICS, diagnostics.identifier(),
                            diagnostics.provenance(), List.of()));
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType capability) {
            return CapabilitySelection.unavailable(capability, "not available in test");
        }
    }
}
