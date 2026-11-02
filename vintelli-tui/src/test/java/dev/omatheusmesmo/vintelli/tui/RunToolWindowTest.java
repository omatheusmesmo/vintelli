package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import org.junit.jupiter.api.Test;

class RunToolWindowTest {

    @Test
    void pilotSelectsConfigurationRunsItRendersOutputAndCanStopIt() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider provider = new FakeRunProvider();
        FakeRegistry registry = new FakeRegistry(provider);
        RunToolWindow window = new RunToolWindow(() -> themes, registry, uiWork, ".");
        window.refreshConfigurations();
        assertTrue(provider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        try (ToolkitTestRunner test = runShell(shell, new Size(100, 30))) {
            Pilot pilot = test.pilot();
            pilot.pause(Duration.ofMillis(100));
            pilot.press('1');
            pilot.pause(Duration.ofMillis(50));
            pilot.press('j');
            pilot.pause(Duration.ofMillis(50));
            assertEquals(1, window.selectedIndex());

            pilot.press(KeyCode.ENTER);
            assertTrue(provider.started.await(5, TimeUnit.SECONDS));
            pilot.pause(Duration.ofMillis(150));
            uiWork.drain();
            assertEquals("idea:Second", provider.startedConfiguration.get());
            assertTrue(window.consoleLines().contains("Second output"));
            assertTrue(window.statusMessage().contains("Running idea:Second"));
            String screen = renderedText(window, uiWork);
            assertTrue(screen.contains("Provider: idea · IDEA MCP"), screen);
            assertTrue(screen.contains("▶ Run [Enter]"), screen);
            assertTrue(screen.contains("Configurations"), screen);
            assertTrue(screen.contains("› idea:Second"), screen);
            assertTrue(screen.contains("Console"), screen);
            assertTrue(screen.contains("Second output"), screen);

            // Lifecycle-looking process output must remain ordinary console text.
            provider.output.accept("Run finished with exit code 0");
            pilot.pause(Duration.ofMillis(50));
            uiWork.drain();
            assertTrue(window.statusMessage().contains("Running idea:Second"));
            Rect toolArea = window.lastArea();
            pilot.click(toolArea.x() + 41, toolArea.y() + 2);
            assertTrue(provider.cancelled.await(5, TimeUnit.SECONDS));
            pilot.pause(Duration.ofMillis(50));
            uiWork.drain();
            assertEquals("Run cancelled", window.statusMessage());
        }
    }

    @Test
    void unavailableAndListingFailuresRenderDistinctStates() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRegistry unavailable = new FakeRegistry(null);
        unavailable.unavailableReason = "IDEA MCP server is disconnected";
        RunToolWindow unavailableWindow = new RunToolWindow(() -> themes, unavailable, uiWork, ".");
        unavailableWindow.refreshConfigurations();
        awaitUi(uiWork, () -> !unavailableWindow.statusMessage().contains("Loading run configurations"));
        assertEquals("IDEA MCP server is disconnected", unavailableWindow.statusMessage());
        assertTrue(unavailableWindow.configurations().isEmpty());
        assertEquals("Run provider unavailable", unavailableWindow.providerLabel());
        assertTrue(renderedText(unavailableWindow, uiWork).contains("IDEA MCP server is disconnected"));

        FakeRunProvider failingProvider = new FakeRunProvider();
        failingProvider.listResult = CapabilityResult.failed(new CapabilityFailure("Malformed response", "bad JSON"));
        FakeRegistry failing = new FakeRegistry(failingProvider);
        RunToolWindow failureWindow = new RunToolWindow(() -> themes, failing, uiWork, ".");
        failureWindow.refreshConfigurations();
        assertTrue(failingProvider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> failureWindow.statusMessage().startsWith("Run configuration listing failed"));
        assertTrue(failureWindow.consoleLines().getFirst().contains("Run configuration listing failed"));
        assertEquals("idea · IDEA MCP", failureWindow.providerLabel());
        assertTrue(renderedText(failureWindow, uiWork).contains("Configuration listing failed."));
    }

    @Test
    void shortPaneKeepsActionsAndConsoleVisibleAndMapsMouseToScrolledConfiguration() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider provider = new FakeRunProvider();
        provider.listResult = CapabilityResult.ok(java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new RunCapability.RunConfiguration("idea:Config" + index, ""))
                .toList());
        RunToolWindow window = new RunToolWindow(() -> themes, new FakeRegistry(provider), uiWork, ".");
        window.refreshConfigurations();
        assertTrue(provider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 20);
        for (int index = 0; index < 19; index++) {
            window.performAction(KeyActions.RUN_SELECT_NEXT);
        }
        Buffer buffer = Buffer.empty(Rect.of(100, 12));
        window.render(Frame.forTesting(buffer), Rect.of(100, 12));
        String screen = bufferText(buffer);
        assertTrue(screen.contains("Provider: idea"), screen);
        assertTrue(screen.contains("▶ Run [Enter]"), screen);
        assertTrue(screen.contains("Configurations"), screen);
        assertTrue(screen.contains("› idea:Config19"), screen);
        assertTrue(screen.contains("Console"), screen);

        // The first visible row is Config16 after scrolling; clicking it must
        // select that item, not a hidden row based on the original list offset.
        assertTrue(window.handleMouse(MouseEvent.press(MouseButton.LEFT, 5, 5)));
        assertEquals(16, window.selectedIndex());
        assertNull(window.actionForMouse(MouseEvent.press(MouseButton.RIGHT, 41, 2)));
    }

    @Test
    void refusesToRunConfigurationAfterProviderChanges() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider listedProvider = new FakeRunProvider();
        FakeRunProvider currentProvider = new FakeRunProvider();
        FakeRegistry registry = new FakeRegistry(listedProvider);
        RunToolWindow window = new RunToolWindow(() -> themes, registry, uiWork, ".");
        window.refreshConfigurations();
        assertTrue(listedProvider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);

        registry.provider = currentProvider;
        window.performAction(KeyActions.RUN_EXECUTE);
        awaitUi(uiWork, () -> window.statusMessage().contains("provider changed"));

        assertTrue(window.configurations().isEmpty());
        assertEquals(1L, currentProvider.started.getCount());
    }

    private static void awaitUi(UiWorkQueue queue, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            queue.drain();
            Thread.sleep(10);
        }
        queue.drain();
        assertTrue(condition.getAsBoolean());
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private static ToolkitTestRunner runShell(VintelliShell shell, Size size) throws Exception {
        ToolkitTestRunner test = ToolkitTestRunner.runTest(shell::root, size);
        test.runner().styleEngine(shell.themes().engine());
        test.runner().eventRouter().addGlobalHandler(shell);
        shell.bindQuit(test.runner()::quit);
        return test;
    }

    private static String renderedText(RunToolWindow window, UiWorkQueue uiWork) {
        Buffer buffer = Buffer.empty(Rect.of(100, 30));
        uiWork.drain();
        window.render(Frame.forTesting(buffer), Rect.of(100, 30));
        return bufferText(buffer);
    }

    private static String bufferText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < buffer.height(); row++) {
            for (int column = 0; column < buffer.width(); column++) {
                text.append(buffer.get(column, row).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static final class FakeRegistry implements CapabilityRegistry {
        private volatile RunCapability provider;
        private String unavailableReason;

        private FakeRegistry(RunCapability provider) {
            this.provider = provider;
        }

        @Override
        public CapabilitySelection<RunCapability> run() {
            if (unavailableReason != null) {
                return CapabilitySelection.unavailable(CapabilityType.RUN, unavailableReason);
            }
            return CapabilitySelection.selected(CapabilityType.RUN, provider);
        }

        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { return unavailable(CapabilityType.DIAGNOSTICS); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() { return List.of(); }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }

    private static final class FakeRunProvider implements RunCapability {
        private final CountDownLatch listed = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicReference<String> startedConfiguration = new AtomicReference<>();
        private volatile java.util.function.Consumer<String> output = ignored -> { };
        private volatile java.util.function.Consumer<RunCompletion> completion = ignored -> { };
        private volatile CapabilityResult<List<RunConfiguration>> listResult = CapabilityResult.ok(List.of(
                new RunConfiguration("idea:First", "First launch"),
                new RunConfiguration("idea:Second", "Second launch")));

        @Override public String identifier() { return "idea"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "IDEA MCP"; }

        @Override
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            listed.countDown();
            return listResult;
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, java.util.function.Consumer<String> output) {
            return start(request, output, ignored -> { });
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, java.util.function.Consumer<String> output,
                java.util.function.Consumer<RunCompletion> completion) {
            startedConfiguration.set(request.configurationName());
            this.output = output;
            this.completion = completion;
            output.accept("Second output");
            started.countDown();
            return CapabilityResult.ok(() -> {
                cancelled.countDown();
                completion.accept(new RunCompletion(RunOutcome.CANCELLED, Optional.empty()));
            });
        }
    }
}
