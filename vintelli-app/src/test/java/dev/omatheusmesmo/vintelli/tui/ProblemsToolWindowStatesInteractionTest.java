package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
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
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProblemsToolWindowStatesInteractionTest {

    private static final Size VIEWPORT = new Size(100, 24);
    private static final Duration WAIT = Duration.ofSeconds(3);
    private static final String REGISTRY_REASON = "no healthy provider for 'diagnostics': "
            + "idea (disconnected: not connected yet), lsp (no language server profile is configured; set "
            + "vintelli.lsp.profiles.<name>.command, for example vintelli.lsp.profiles.jdtls.command=jdtls)";

    @TempDir
    Path project;

    @Test
    void aFreshInstallShowsOneReasonAndTheNextStepWithoutTheRegistryWording() throws Exception {
        ShellThemes themes = themes();
        BuiltinEditor editor = editorWith(themes, openedFile());
        ProblemsRegistry registry = new ProblemsRegistry(null, REGISTRY_REASON);
        try (ToolWindowPilotFixture fixture = open(new ProblemsToolWindow(registry, editor, () -> themes,
                Optional.empty()), themes, registry)) {
            fixture.press('6', KeyModifiers.ALT);

            assertTrue(fixture.await(() -> windowText(fixture).contains("No diagnostics provider configured"),
                    WAIT), windowText(fixture));
            String screen = windowText(fixture);
            assertTrue(screen.contains("vintelli.lsp.profiles.jdtls.command=jdtls"), screen);
            assertTrue(screen.contains("Problems Unavailable"), screen);
            assertEquals(0, occurrences(screen, "no healthy provider"), screen);
            assertEquals(0, occurrences(screen, "not connected yet"), screen);
            assertEquals(1, occurrences(screen, "No diagnostics provider configured"), screen);
        }
    }

    @Test
    void aHealthyProviderWithNoProblemsSaysSo() throws Exception {
        ShellThemes themes = themes();
        BuiltinEditor editor = editorWith(themes, openedFile());
        ProblemsRegistry registry = new ProblemsRegistry(new TestDiagnostics(List.of()), null);
        try (ToolWindowPilotFixture fixture = open(new ProblemsToolWindow(registry, editor, () -> themes,
                Optional.empty()), themes, registry)) {
            fixture.press('6', KeyModifiers.ALT);

            assertTrue(fixture.await(() -> windowText(fixture).contains("No problems in open files"), WAIT),
                    windowText(fixture));
            assertTrue(windowText(fixture).contains("0 errors, 0 warnings, 0 others"), windowText(fixture));
        }
    }

    @Test
    void rowsShowSeverityPositionAndHeaderCountsThroughTheShell() throws Exception {
        ShellThemes themes = themes();
        Path file = openedFile();
        BuiltinEditor editor = editorWith(themes, file);
        ProblemsRegistry registry = new ProblemsRegistry(new TestDiagnostics(List.of(
                new Diagnostic(file, 3, 2, 3, 8, Severity.ERROR, "jdtls", "cannot find symbol"),
                new Diagnostic(file, 6, 0, 6, 4, Severity.WARNING, "jdtls", "unused import"),
                new Diagnostic(file, 7, 4, 7, 9, Severity.INFORMATION, "jdtls", "field can be final"))), null);
        try (ToolWindowPilotFixture fixture = open(new ProblemsToolWindow(registry, editor, () -> themes,
                Optional.empty()), themes, registry)) {
            fixture.press('6', KeyModifiers.ALT);

            assertTrue(fixture.await(() -> windowText(fixture).contains("cannot find symbol"), WAIT),
                    windowText(fixture));
            String screen = windowText(fixture);
            assertTrue(screen.contains("1 error, 1 warning, 1 other"), screen);
            assertTrue(screen.contains("ERROR L4:C3 jdtls cannot find symbol"), screen);
            assertTrue(screen.contains("WARN L7:C1 jdtls unused import"), screen);
            assertTrue(screen.contains("INFO L8:C5 jdtls field can be final"), screen);
        }
    }

    /**
     * The text inside the Problems pane only, with every run of whitespace (including the line breaks of wrapped
     * rows) collapsed to one space, so a phrase that wraps still reads as one phrase.
     */
    private static String windowText(ToolWindowPilotFixture fixture) {
        Rect area = fixture.pilot().findElement("tool-problems");
        List<String> rows = fixture.renderedText().lines().toList();
        StringBuilder text = new StringBuilder();
        for (int y = area.y(); y < area.y() + area.height() && y < rows.size(); y++) {
            String row = rows.get(y);
            text.append(row, Math.min(area.x(), row.length()), Math.min(area.x() + area.width(), row.length()))
                    .append('\n');
        }
        return text.toString().replaceAll("\\s+", " ").strip();
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + fragment.length())) {
            count++;
        }
        return count;
    }

    private static ToolWindowPilotFixture open(ProblemsToolWindow window, ShellThemes themes,
            CapabilityRegistry registry) throws Exception {
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(window), new ShellState(), themes)
                .withCapabilityRegistry(registry));
        shell.useUiWorkQueue(new UiWorkQueue());
        return ToolWindowPilotFixture.open(shell, VIEWPORT);
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private BuiltinEditor editorWith(ShellThemes themes, Path file) {
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(file, 0));
        return editor;
    }

    private Path openedFile() throws IOException {
        project = project.toRealPath();
        return Files.writeString(project.resolve("Broken.java"), String.join("\n",
                "class Broken {", "  void first() {}", "  void second() {}", "  void third() {}",
                "  void fourth() {}", "  void fifth() {}", "  void sixth() {}", "  void seventh() {}", "}"));
    }

    private record TestDiagnostics(List<Diagnostic> diagnostics) implements DiagnosticsCapability {
        @Override public String identifier() { return "jdtls"; }
        @Override public String provenance() { return "jdtls over stdio"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public CapabilityResult<List<Diagnostic>> diagnostics(Path file) {
            return CapabilityResult.ok(diagnostics.stream()
                    .filter(problem -> problem.filePath().toAbsolutePath().normalize().equals(file.normalize()))
                    .toList());
        }
    }

    private record ProblemsRegistry(DiagnosticsCapability provider, String unavailableReason)
            implements CapabilityRegistry {
        @Override public CapabilitySelection<RunCapability> run() { return unavailable(CapabilityType.RUN); }
        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() {
            return provider == null
                    ? CapabilitySelection.unavailable(CapabilityType.DIAGNOSTICS, unavailableReason)
                    : CapabilitySelection.selected(CapabilityType.DIAGNOSTICS, provider);
        }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() {
            return provider == null
                    ? List.of(CapabilityStatus.unavailable(CapabilityType.DIAGNOSTICS, unavailableReason))
                    : List.of(CapabilityStatus.available(CapabilityType.DIAGNOSTICS, provider.identifier(),
                            provider.provenance(), List.of()));
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }
}
