package dev.omatheusmesmo.vintelli.tui;

import static dev.omatheusmesmo.vintelli.tui.ToolWindowInteractionAssertions.assertActivationParity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProblemsToolWindowInteractionContractTest {

    private static final Size VIEWPORT = new Size(100, 20);

    @TempDir
    Path project;

    @Test
    void keyboardAndPointerNavigateTheSameProblemAndKeepWindowFocus() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "Broken.java:6; opened=Broken.java:6",
                KeyActions.PROBLEMS_NAVIGATE,
                "editor-text-area",
                0,
                "Broken.java:6");

        assertActivationParity("Problems diagnostic navigation", expected,
                this::newSession,
                ProblemsToolWindowInteractionContractTest::navigateWithKeyboard,
                this::newSession,
                ProblemsToolWindowInteractionContractTest::navigateWithPointer);
    }

    private ToolWindowInteractionAssertions.Session newSession() throws Exception {
        project = project.toRealPath();
        Path brokenFile = Files.writeString(project.resolve("Broken.java"), String.join("\n",
                "class Broken {", "  void first() {}", "  void second() {}", "  void third() {}",
                "  void fourth() {}", "  void fifth() {}", "}"));
        Path otherFile = Files.writeString(project.resolve("Other.java"), String.join("\n",
                "class Other {", "  void warning() {}", "}"));
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(brokenFile, 0));
        editor.open(new EditorBridge.OpenRequest(otherFile, 0));

        TestDiagnostics provider = new TestDiagnostics(List.of(
                new Diagnostic(brokenFile, 1, 2, 1, 8, Severity.ERROR, "jdtls", "first problem"),
                new Diagnostic(brokenFile, 5, 2, 5, 8, Severity.ERROR, "jdtls", "broken target"),
                new Diagnostic(otherFile, 1, 2, 1, 8, Severity.WARNING, "jdtls", "other target")));
        TestRegistry registry = new TestRegistry(provider);
        ProblemsToolWindow window = new ProblemsToolWindow(registry, editor, () -> themes);
        window.refresh();
        assertTrue(await(() -> window.problems().size() == 3), "diagnostics did not load");

        ShellState state = new ShellState();
        VintelliShell shell = new VintelliShell(List.of(window, editor), state, themes, registry);
        ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, VIEWPORT);
        ToolWindowInteractionAssertions.Session session = new ToolWindowInteractionAssertions.Session(fixture,
                () -> observe(window, editor, state, fixture), new ProblemsContext(window));
        fixture.pilot().pause(Duration.ofMillis(100));
        return session;
    }

    private static void navigateWithKeyboard(ToolWindowInteractionAssertions.Session session) {
        ToolWindowPilotFixture fixture = session.fixture();
        fixture.press('3', KeyModifiers.ALT);
        assertEquals("tool-problems", fixture.testRunner().runner().focusManager().focusedId(),
                "activating Problems transfers TamboUI focus to its tool window");
        fixture.press(KeyCode.DOWN);
        fixture.press(KeyCode.ENTER);
    }

    private static void navigateWithPointer(ToolWindowInteractionAssertions.Session session) {
        ToolWindowPilotFixture fixture = session.fixture();
        var pilot = fixture.pilot();
        fixture.press('3', KeyModifiers.ALT);
        Rect area = pilot.findElement("tool-problems");
        assertTrue(area.height() > 5, "Problems rows are not visible in the test viewport");
        pilot.click(area.x() + 2, area.y() + 3);
    }

    private static ToolWindowInteractionAssertions.Observation observe(ProblemsToolWindow window,
            BuiltinEditor editor, ShellState state, ToolWindowPilotFixture fixture) {
        int selectedIndex = window.selectedIndex();
        List<Diagnostic> diagnostics = window.problems();
        String selected = selectedIndex >= 0 && selectedIndex < diagnostics.size()
                ? relative(diagnostics.get(selectedIndex), editor.project()) : "";
        BuiltinEditor.DocumentView document = editor.activeDocument().orElse(null);
        String opened = document == null ? "none"
                : relative(Path.of(document.file()), editor.project()) + ":" + (document.line() + 1);
        String rendered = fixture.renderedText();
        boolean brokenProblemSelected = rendered.lines()
                .anyMatch(line -> line.contains("> ERROR L6") && line.contains("broken target"));
        String renderedSelection = brokenProblemSelected ? "Broken.java:6" : "";
        // The production shell does not expose action telemetry; opening the exact target line proves that the
        // mapped named action reached its handler. Shell status is a transient VCS refresh surface.
        return new ToolWindowInteractionAssertions.Observation(
                selected + "; opened=" + opened,
                opened.equals("Broken.java:6") ? KeyActions.PROBLEMS_NAVIGATE : "",
                fixture.testRunner().runner().focusManager().focusedId(),
                0,
                renderedSelection,
                "Pilot area=" + fixture.pilot().findElement("tool-problems")
                        + "\nShell state=" + state.snapshot() + "\nRendered buffer:\n" + rendered);
    }

    private static String relative(Diagnostic diagnostic, Path root) {
        return relative(diagnostic.filePath(), root) + ":" + (diagnostic.startLine() + 1);
    }

    private static String relative(Path file, Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.isAbsolute() ? file.normalize() : normalizedRoot.resolve(file).normalize();
        try {
            normalizedRoot = normalizedRoot.toRealPath();
            normalizedFile = normalizedFile.toRealPath();
        } catch (IOException ignored) {
            // Keep the normalized paths for a useful assertion if the file was concurrently removed.
        }
        return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
    }

    private boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private record ProblemsContext(ProblemsToolWindow window) implements AutoCloseable {
        @Override
        public void close() {
            window.close();
        }
    }

    private record TestDiagnostics(List<Diagnostic> diagnostics) implements DiagnosticsCapability {
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

    private record TestRegistry(DiagnosticsCapability provider) implements CapabilityRegistry {
        @Override public CapabilitySelection<RunCapability> run() { return unavailable(CapabilityType.RUN); }
        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() {
            return CapabilitySelection.selected(CapabilityType.DIAGNOSTICS, provider);
        }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() { return List.of(); }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }
}
