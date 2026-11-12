package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.tui.RunToolWindowInteractionContractTest.FakeDebugCapability;
import dev.omatheusmesmo.vintelli.tui.RunToolWindowInteractionContractTest.FakeRegistry;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Size;
import dev.tamboui.layout.Rect;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugLaunchInteractionTest {
    @TempDir Path project;

    @Test
    void steppingActionsAreReachableByKeyboardPaletteAndMouse() throws Exception {
        DebugProvider provider = new DebugProvider();
        FakeDebugCapability debug = new FakeDebugCapability();
        UiWorkQueue queue = new UiWorkQueue();
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        var registry = new FakeRegistry(provider, debug);
        String sourceText = "class Main {\n  void first() {}\n  void second() {}\n  void target() {}\n}\n";
        Path source = Files.writeString(project.resolve("Main.java"), sourceText);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(source, 0));
        registry.editorBridge = editor;
        debug.sourcePath = source.toString();
        RunToolWindow window = new RunToolWindow(() -> themes, registry, queue, ".", null, 5123);
        window.refreshConfigurations();
        await(queue, () -> !window.configurations().isEmpty());
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(window, editor), new ShellState(), themes)
                .withCapabilityRegistry(registry));
        try (ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, new Size(140, 30))) {
            assertTrue(window.performAction(KeyActions.RUN_DEBUG), window.statusMessage());
            await(queue, () -> window.running() || !window.statusMessage().startsWith("Starting "));
            assertTrue(window.running(), window.statusMessage());

            await(queue, () -> editor.activeDocument().map(document -> document.line() == 3).orElse(false));
            assertEquals(sourceText, editor.activeDocument().orElseThrow().text(),
                    "revealing a stopped frame must not change source text");
            focusRunTool(fixture, window);
            fixture.press('o');
            await(queue, () -> window.statusMessage().equals("Step over requested"));
            fixture.press('p', KeyModifiers.CTRL);
            for (char ch : "Resume Debug Session".toCharArray()) fixture.pilot().press(ch);
            fixture.press(KeyCode.ENTER);
            await(queue, () -> window.statusMessage().equals("Resume requested"));
            assertTrue(fixture.renderedText().contains("Step over unavailable: session is running"));
            window.performAction(KeyActions.DEBUG_STEP_OVER);
            assertTrue(window.statusMessage().contains("session is not stopped"));

            Rect area = fixture.pilot().findElement("tool-run");
            fixture.pilot().click(area.x() + 57, area.y() + 1);
            await(queue, () -> window.statusMessage().equals("Pause requested"));
            focusRunTool(fixture, window);
            fixture.press('i');
            await(queue, () -> window.statusMessage().equals("Step into requested"));
            focusRunTool(fixture, window);
            fixture.press('u');
            await(queue, () -> window.statusMessage().equals("Step out requested"));
            assertEquals(List.of("stepOver", "resume", "pause", "stepInto", "stepOut"), debug.executionActions);
            assertTrue(fixture.renderedText().contains("Resume  Pause  Over  Into  Out"));
            window.performAction(KeyActions.RUN_STOP);
        }
    }

    private static void focusRunTool(ToolWindowPilotFixture fixture, RunToolWindow window) {
        fixture.renderedText();
        fixture.press('4', KeyModifiers.ALT);
        String focused = fixture.testRunner().runner().focusManager().focusedId();
        assertEquals("tool-run", focused, "Run window should be focused before keyboard stepping; status="
                + window.statusMessage() + ", focus=" + focused);
    }

    @Test
    void toolbarAndPaletteLaunchTheSameConfigurationAndStopClearsDebugState() throws Exception {
        for (boolean pointer : List.of(true, false)) {
            DebugProvider provider = new DebugProvider();
            FakeDebugCapability debugCapability = new FakeDebugCapability();
            UiWorkQueue queue = new UiWorkQueue();
            ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
            var registry = new FakeRegistry(provider, debugCapability);
            RunToolWindow window = new RunToolWindow(() -> themes, registry, queue, ".", null, 5123);
            window.refreshConfigurations();
            await(queue, () -> !window.configurations().isEmpty());
            assertTrue(window.debugAvailable());
            VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(window), new ShellState(), themes)
                    .withCapabilityRegistry(registry));
            try (ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, new Size(140, 30))) {
                if (pointer) {
                    String row = fixture.renderedText().lines().findFirst().orElseThrow();
                    int debug = row.indexOf(MainToolbar.Glyphs.UNICODE.debug());
                    if (debug < 0) {
                        int ascii = row.lastIndexOf(" d ");
                        debug = ascii < 0 ? -1 : ascii + 1;
                    }
                    assertTrue(debug >= 0, row);
                    fixture.pilot().click(debug, 0);
                } else {
                    fixture.press('p', KeyModifiers.CTRL);
                    for (char ch : "Debug Selected Configuration".toCharArray()) fixture.pilot().press(ch);
                    fixture.press(KeyCode.ENTER);
                }
                await(queue, window::running);
                assertEquals("maven:quarkus:dev", provider.started);
                assertEquals(5123, provider.port);
                assertEquals(5123, debugCapability.attachedPort.get());
                assertTrue(window.debugging());
                assertFalse(window.debugAvailable());
                assertTrue(fixture.renderedText().contains("Debug |"), fixture.renderedText());
                window.performAction(KeyActions.RUN_STOP);
                await(queue, () -> !window.running());
                await(queue, () -> debugCapability.disconnects.get() == 1);
                assertFalse(window.debugging());
                assertTrue(window.debugAvailable());
                assertFalse(fixture.renderedText().contains("Debug |"));
                window.selectConfiguration("maven:compile");
                assertFalse(window.debugAvailable());
                window.performAction(KeyActions.RUN_DEBUG);
                assertTrue(window.statusMessage().contains("No debug launch"));
                assertEquals(1, provider.starts);
            }
        }
    }

    private static void await(UiWorkQueue queue, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            queue.drain();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class DebugProvider implements RunCapability {
        private volatile String started;
        private volatile int port;
        private int starts;
        public String identifier() { return "test"; }
        public String provenance() { return "debug twin"; }
        public ProviderHealth health() { return ProviderHealth.available(); }
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            return CapabilityResult.ok(List.of(new RunConfiguration("maven:quarkus:dev", "Application"),
                    new RunConfiguration("maven:compile", "Compile")));
        }
        public DebugSupport debugSupport(String id) {
            return new DebugSupport(id.equals("maven:quarkus:dev"), "No debug launch for " + id);
        }
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> lines) {
            throw new AssertionError("Debug must use startDebug");
        }
        public CapabilityResult<RunHandle> startDebug(RunRequest request, int port, Consumer<String> lines,
                Consumer<RunCompletion> completed) {
            this.port = port;
            started = request.configurationName();
            starts++;
            lines.accept("Debug: attach to 127.0.0.1:" + port);
            return CapabilityResult.ok(() -> completed.accept(new RunCompletion(RunOutcome.CANCELLED, Optional.empty())));
        }
    }
}
