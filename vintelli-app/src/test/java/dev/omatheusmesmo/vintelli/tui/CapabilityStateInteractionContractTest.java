package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
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
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityStateInteractionContractTest {

    private static final Size VIEWPORT = new Size(100, 30);
    private static final String NO_PROVIDER = "no provider is wired";

    @TempDir
    Path project;

    @Test
    void zeroProvidersShowAnExplicitReasonInEveryWindowWithoutUserAction() throws Exception {
        ScriptedRegistry registry = new ScriptedRegistry();
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        Path brokenFile = openedBrokenFile();
        BuiltinEditor editor = editor(themes, brokenFile);

        RunToolWindow run = new RunToolWindow(() -> themes, registry, uiWork, ".");
        run.refreshConfigurations();
        try (ToolWindowPilotFixture fixture = open(List.of(run), themes, registry, uiWork)) {
            fixture.press('4', KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Run unavailable: " + NO_PROVIDER),
                    Duration.ofSeconds(3)), fixture.renderedText());
            assertTrue(fixture.renderedText().contains("Read File unavailable"), fixture.renderedText());
        }
        try (ToolWindowPilotFixture fixture = open(List.of(new TerminalToolWindow(() -> themes, registry)),
                themes, registry, uiWork)) {
            fixture.press('2', KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Terminal unavailable: " + NO_PROVIDER),
                    Duration.ofSeconds(3)), fixture.renderedText());
        }
        try (ToolWindowPilotFixture fixture = open(List.of(new ProblemsToolWindow(registry, editor, () -> themes)),
                themes, registry, uiWork)) {
            fixture.press('6', KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Problems unavailable: " + NO_PROVIDER),
                    Duration.ofSeconds(3)), fixture.renderedText());
        }
    }

    @Test
    void activeProvidersShowIdentifierAndProvenance() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.run = new FakeRun();
        registry.terminal = new FakeTerminal();
        registry.diagnostics = new FakeDiagnostics(List.of());
        BuiltinEditor editor = editor(themes, openedBrokenFile());

        try (ToolWindowPilotFixture fixture = open(List.of(new TerminalToolWindow(() -> themes, registry)),
                themes, registry, uiWork)) {
            fixture.press('2', KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Terminal: pty (local shell)"),
                    Duration.ofSeconds(3)), fixture.renderedText());
        }

        RunToolWindow run = new RunToolWindow(() -> themes, registry, uiWork, ".");
        run.refreshConfigurations();
        try (ToolWindowPilotFixture fixture = open(List.of(run), themes, registry, uiWork)) {
            fixture.press('4', KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Run: idea (IDEA MCP)"),
                    Duration.ofSeconds(3)), fixture.renderedText());
        }
        try (ToolWindowPilotFixture fixture = open(List.of(new ProblemsToolWindow(registry, editor, () -> themes)),
                themes, registry, uiWork)) {
            fixture.press('6', KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Problems: jdtls (jdtls over stdio)"),
                    Duration.ofSeconds(3)), fixture.renderedText());
        }
    }

    @Test
    void skippedProvidersRenderNextToTheActiveProvider() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.diagnostics = new FakeDiagnostics(List.of(broken("broken target")));
        registry.skipped = List.of(new SkippedProvider("idea", "not connected"));
        BuiltinEditor editor = editor(themes, openedBrokenFile());

        try (ToolWindowPilotFixture fixture = open(List.of(new ProblemsToolWindow(registry, editor, () -> themes)),
                themes, registry, uiWork)) {
            fixture.press('6', KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("skipped idea: not connected"),
                    Duration.ofSeconds(3)), fixture.renderedText());
            String screen = fixture.renderedText();
            assertTrue(screen.contains("Problems: jdtls (jdtls over stdio)"), screen);
            assertTrue(screen.contains("broken target"), screen);
        }
    }

    @Test
    void providerThatComesBackHealthyIsPickedUpByTheNextRefresh() throws Exception {
        ShellThemes themes = themes();
        ScriptedRegistry registry = new ScriptedRegistry();
        UiWorkQueue uiWork = new UiWorkQueue();
        BuiltinEditor editor = editor(themes, openedBrokenFile());

        try (ToolWindowPilotFixture fixture = open(List.of(new ProblemsToolWindow(registry, editor, () -> themes)),
                themes, registry, uiWork)) {
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Problems unavailable: " + NO_PROVIDER),
                    Duration.ofSeconds(3)), fixture.renderedText());

            registry.diagnostics = new FakeDiagnostics(List.of(broken("broken target")));
            fixture.press('6', KeyModifiers.ALT);
            fixture.press('r');

            assertTrue(fixture.await(() -> fixture.renderedText().contains("Problems: jdtls (jdtls over stdio)"),
                    Duration.ofSeconds(3)), fixture.renderedText());
        }
    }

    @Test
    void blockingSnapshotNeverBlocksRenderingAndRenderNeverReadsIt() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.run = new FakeRun();
        registry.gate = new CountDownLatch(1);

        try (ToolWindowPilotFixture fixture = open(List.of(new RunToolWindow(() -> themes, registry, uiWork, ".")),
                themes, registry, uiWork)) {
            fixture.press('4', KeyModifiers.ALT);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                assertTrue(fixture.renderedText().contains("Run: checking provider"), fixture.renderedText());
            });
            registry.gate.countDown();
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Run: idea (IDEA MCP)")
                    && fixture.renderedText().contains("Read File unavailable"), Duration.ofSeconds(3)),
                    fixture.renderedText());
            int reads = registry.snapshots.get();
            for (int frame = 0; frame < 20; frame++) {
                fixture.renderedText();
            }
            assertEquals(reads, registry.snapshots.get(), "rendering must not call CapabilityRegistry.snapshot()");
        }
    }

    @Test
    void narrowWindowClipsSkippedProviderTextAndKeepsTheRowsVisible() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        ScriptedRegistry registry = new ScriptedRegistry();
        registry.diagnostics = new FakeDiagnostics(List.of(broken("broken target")));
        registry.skipped = List.of(new SkippedProvider("idea",
                "not connected to the IDEA MCP server after three retries and a long backoff"));
        BuiltinEditor editor = editor(themes, openedBrokenFile());

        try (ToolWindowPilotFixture fixture = open(List.of(new ProblemsToolWindow(registry, editor, () -> themes)),
                themes, registry, uiWork)) {
            fixture.press('6', KeyModifiers.ALT);
            fixture.resize(80, 16);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("skipped idea: not connected"),
                    Duration.ofSeconds(3)), fixture.renderedText());
            String screen = fixture.renderedText();
            assertTrue(screen.contains("broken target"), screen);
            assertTrue(screen.contains("Problems: jdtls"), screen);
            assertFalse(screen.contains("after three retries"), screen);
        }
    }

    private static ToolWindowPilotFixture open(List<ToolWindow> windows, ShellThemes themes, CapabilityRegistry registry,
            UiWorkQueue uiWork) throws Exception {
        VintelliShell shell = new VintelliShell(ShellConfig.of(windows, new ShellState(), themes)
                .withCapabilityRegistry(registry));
        shell.useUiWorkQueue(uiWork);
        return ToolWindowPilotFixture.open(shell, VIEWPORT);
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private Path openedBrokenFile() throws IOException {
        project = project.toRealPath();
        return Files.writeString(project.resolve("Broken.java"), String.join("\n",
                "class Broken {", "  void first() {}", "  void second() {}", "  void third() {}",
                "  void fourth() {}", "  void fifth() {}", "}"));
    }

    private BuiltinEditor editor(ShellThemes themes, Path brokenFile) {
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(brokenFile, 0));
        return editor;
    }

    private static Diagnostic broken(String message) {
        return new Diagnostic(Path.of("Broken.java"), 5, 2, 5, 8, Severity.ERROR, "jdtls", message);
    }

    /**
     * Registry whose rows follow the fields the test sets. Its snapshot counts calls, and it can hold each call
     * on a gate so a test can prove the render thread never waits for it.
     */
    private static final class ScriptedRegistry implements CapabilityRegistry {
        private volatile RunCapability run;
        private volatile TerminalCapability terminal;
        private volatile DiagnosticsCapability diagnostics;
        private volatile List<SkippedProvider> skipped = List.of();
        private volatile CountDownLatch gate;
        private final AtomicInteger snapshots = new AtomicInteger();

        @Override public CapabilitySelection<RunCapability> run() {
            RunCapability current = run;
            return current == null ? CapabilitySelection.unavailable(CapabilityType.RUN, NO_PROVIDER)
                    : CapabilitySelection.selected(CapabilityType.RUN, current);
        }
        @Override public CapabilitySelection<TerminalCapability> terminal() {
            TerminalCapability current = terminal;
            return current == null ? CapabilitySelection.unavailable(CapabilityType.TERMINAL, NO_PROVIDER)
                    : CapabilitySelection.selected(CapabilityType.TERMINAL, current);
        }
        @Override public CapabilitySelection<ReadFileCapability> readFile() {
            return CapabilitySelection.unavailable(CapabilityType.READ_FILE, NO_PROVIDER);
        }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() {
            DiagnosticsCapability current = diagnostics;
            return current == null ? CapabilitySelection.unavailable(CapabilityType.DIAGNOSTICS, NO_PROVIDER)
                    : CapabilitySelection.selected(CapabilityType.DIAGNOSTICS, current);
        }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() {
            return CapabilitySelection.unavailable(CapabilityType.CALL_HIERARCHY, NO_PROVIDER);
        }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() {
            return CapabilitySelection.unavailable(CapabilityType.CODE_ACTION, NO_PROVIDER);
        }
        @Override public CapabilitySelection<EditorBridge> editorBridge() {
            return CapabilitySelection.unavailable(CapabilityType.EDITOR_BRIDGE, NO_PROVIDER);
        }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() {
            return CapabilitySelection.unavailable(CapabilityType.VERSION_CONTROL, NO_PROVIDER);
        }

        @Override
        public List<CapabilityStatus> snapshot() {
            snapshots.incrementAndGet();
            CountDownLatch hold = gate;
            if (hold != null) {
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            List<CapabilityStatus> rows = new ArrayList<>();
            RunCapability current = run;
            rows.add(current == null ? CapabilityStatus.unavailable(CapabilityType.RUN, NO_PROVIDER)
                    : CapabilityStatus.available(CapabilityType.RUN, current.identifier(), current.provenance(),
                            List.of()));
            TerminalCapability shell = terminal;
            rows.add(shell == null ? CapabilityStatus.unavailable(CapabilityType.TERMINAL, NO_PROVIDER)
                    : CapabilityStatus.available(CapabilityType.TERMINAL, shell.identifier(), shell.provenance(),
                            List.of()));
            rows.add(CapabilityStatus.unavailable(CapabilityType.READ_FILE, NO_PROVIDER));
            DiagnosticsCapability problems = diagnostics;
            rows.add(problems == null ? CapabilityStatus.unavailable(CapabilityType.DIAGNOSTICS, NO_PROVIDER)
                    : CapabilityStatus.available(CapabilityType.DIAGNOSTICS, problems.identifier(),
                            problems.provenance(), skipped));
            return rows;
        }

        /** Keeps the status bar from calling {@link #snapshot()} while frames are painted. */
        @Override
        public List<CapabilityStatusWidgets> statusBarSnapshot() {
            return List.of();
        }
    }

    private static final class FakeTerminal implements TerminalCapability {
        @Override public String identifier() { return "pty"; }
        @Override public String provenance() { return "local shell"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public CapabilityResult<TerminalResult> execute(TerminalRequest request) {
            return CapabilityResult.failed(new CapabilityFailure("not-used", "not used in this test"));
        }
    }

    private static final class FakeRun implements RunCapability {
        @Override public String identifier() { return "idea"; }
        @Override public String provenance() { return "IDEA MCP"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            return CapabilityResult.ok(List.of(new RunConfiguration("idea:First", "First launch")));
        }
        @Override public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output) {
            return CapabilityResult.ok(() -> { });
        }
    }

    private record FakeDiagnostics(List<Diagnostic> diagnostics) implements DiagnosticsCapability {
        @Override public String identifier() { return "jdtls"; }
        @Override public String provenance() { return "jdtls over stdio"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public CapabilityResult<List<Diagnostic>> diagnostics(Path file) {
            return CapabilityResult.ok(diagnostics);
        }
    }
}
