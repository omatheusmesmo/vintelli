package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugCapabilities;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugFrame;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugScope;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugThread;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugVariable;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tfx.Effect;
import dev.tamboui.tfx.TFxDuration;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunToolWindowTest {

    @TempDir
    Path directory;

    @Test
    void runCompletionMapsOutcomesToExplicitVisibleStates() {
        assertEquals(RunMotionState.SUCCEEDED,
                RunMotionState.completed(new RunCapability.RunCompletion(RunCapability.RunOutcome.EXITED,
                        Optional.of(0))));
        assertEquals(RunMotionState.FAILED,
                RunMotionState.completed(new RunCapability.RunCompletion(RunCapability.RunOutcome.EXITED,
                        Optional.of(1))));
        assertEquals(RunMotionState.FAILED,
                RunMotionState.completed(new RunCapability.RunCompletion(RunCapability.RunOutcome.TIMED_OUT,
                        Optional.empty())));
        assertEquals(RunMotionState.STOPPED,
                RunMotionState.completed(new RunCapability.RunCompletion(RunCapability.RunOutcome.CANCELLED,
                        Optional.empty())));
    }

    @Test
    void reducedMotionFramesKeepTheLifecycleGlyphStaticAndAdvanceOnlyElapsedTime() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider provider = new FakeRunProvider();
        AtomicLong now = new AtomicLong();
        RunToolWindow window = new RunToolWindow(() -> themes, new FakeRegistry(provider), uiWork, ".",
                null, 5005, Optional.empty(), now::get, true, IconMode.ASCII);
        window.refreshConfigurations();
        assertTrue(provider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);

        assertEquals(">", window.icon());
        window.performAction(KeyActions.RUN_EXECUTE);
        assertTrue(provider.started.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.runState() == RunMotionState.RUNNING);
        assertEquals("|", window.icon());
        assertEquals("|", window.toolbarGlyph());
        assertEquals("|", window.stateGlyph(false));
        assertEquals(KeyActions.RUN_STOP, window.actionForKey(KeyEvent.ofChar('s')));
        assertEquals("00:00", window.toolbarElapsedText());

        String first = renderHeader(window, 0, 3);
        now.set(9_000_000_000L);
        String later = renderHeader(window, 0, 3);
        assertEquals(first.replace("00:00", "elapsed"), later.replace("00:09", "elapsed"));
        assertEquals("00:09", window.toolbarElapsedText());
        assertTrue(first.chars().allMatch(character -> character < 128), first);
    }

    @Test
    void activeRunAsciiPulseUsesAsciiGlyphsAndStopsWithTheLastSample() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider provider = new FakeRunProvider();
        AtomicLong now = new AtomicLong();
        RunToolWindow window = new RunToolWindow(() -> themes, new FakeRegistry(provider), uiWork, ".",
                null, 5005, Optional.empty(), now::get, false, IconMode.ASCII);
        window.refreshConfigurations();
        assertTrue(provider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);
        window.performAction(KeyActions.RUN_EXECUTE);
        assertTrue(provider.started.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.runState() == RunMotionState.RUNNING);
        assertEquals("|", window.icon());
        assertEquals("|", window.toolbarGlyph());
        assertEquals("|", window.stateGlyph(false));

        now.set(1_000_000_000L);
        window.tickMotion();
        String body = renderedText(window, uiWork, Rect.of(100, 8));
        assertTrue(body.contains("Console"), body);
        assertTrue(body.chars().allMatch(character -> character < 128), body);

        String console = body.lines().filter(line -> line.contains("Console")).findFirst().orElseThrow();
        String frozenPulse = console.substring(console.indexOf("Console") + "Console   ".length()).strip();
        assertEquals(12, frozenPulse.length(), frozenPulse);
        assertTrue(frozenPulse.chars().allMatch(character -> "_.-^".indexOf(character) >= 0), frozenPulse);
        window.performAction(KeyActions.RUN_STOP);
        assertTrue(provider.cancelled.await(5, TimeUnit.SECONDS));
        String stopping = renderedText(window, uiWork, Rect.of(100, 8));
        String stoppingConsole = stopping.lines().filter(line -> line.contains("Console")).findFirst().orElseThrow();
        assertEquals(frozenPulse,
                stoppingConsole.substring(stoppingConsole.indexOf("Console") + "Console   ".length()).strip());
    }

    @Test
    void completedHeaderRerunsAndClicksOnTheFormerBodyControlRowDoNothing() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider provider = new FakeRunProvider();
        AtomicLong now = new AtomicLong();
        RunToolWindow window = new RunToolWindow(() -> themes, new FakeRegistry(provider), uiWork, ".",
                null, 5005, Optional.empty(), now::get, false, IconMode.ASCII);
        window.refreshConfigurations();
        assertTrue(provider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);
        window.performAction(KeyActions.RUN_EXECUTE);
        assertTrue(provider.started.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.runState() == RunMotionState.RUNNING);
        provider.completion.accept(new RunCapability.RunCompletion(RunCapability.RunOutcome.EXITED, Optional.of(0)));
        awaitUi(uiWork, () -> window.runState() == RunMotionState.SUCCEEDED);
        assertNull(window.toolbarElapsedText(), "elapsed time is hidden when the run is complete");
        now.addAndGet(600_000_000L);
        assertFalse(window.motionAnimating());
        assertEquals(KeyActions.RUN_EXECUTE, window.actionForKey(KeyEvent.ofChar('r')));

        String header = renderHeader(window, 0, 3);
        int rerunCell = -1;
        for (int x = 0; x < 120; x++) {
            if (KeyActions.RUN_EXECUTE.equals(window.actionForMouse(MouseEvent.press(MouseButton.LEFT, x, 3)))) {
                rerunCell = x;
                break;
            }
        }
        assertTrue(rerunCell >= 0, "the rendered header did not expose its Rerun control: " + header);
        window.renderBody(Frame.forTesting(Buffer.empty(new Rect(0, 4, 120, 8))), new Rect(0, 4, 120, 8));
        assertNull(window.actionForMouse(MouseEvent.press(MouseButton.LEFT, 25, 5)),
                "the first console row must not keep the old Run action hit target");
    }

    @Test
    void theShellTickerSchedulesNoFrameWhileNoRunIsActiveAndStaysBoundedWhileOneRuns() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider provider = new FakeRunProvider();
        AtomicLong now = new AtomicLong();
        RunToolWindow window = new RunToolWindow(() -> themes, new FakeRegistry(provider), uiWork, ".",
                null, 5005, Optional.empty(), now::get, false, IconMode.ASCII);
        AtomicInteger redraws = new AtomicInteger();
        ShellTicker ticker = new ShellTicker(window::motionTickRate, new ReentrantLock(), window::motionAnimating,
                window::tickMotion);
        ticker.bindRedraw(redraws::incrementAndGet);
        window.refreshConfigurations();
        assertTrue(provider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);

        assertFalse(window.motionAnimating(), "an idle Run window animates nothing");
        ticker.sync();
        ticker.beat();
        assertEquals(0, redraws.get(), "an idle Run window requests no frame");

        window.performAction(KeyActions.RUN_EXECUTE);
        assertTrue(provider.started.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.runState() == RunMotionState.RUNNING);
        assertTrue(window.motionAnimating());
        assertTrue(window.motionTickRate().compareTo(Duration.ofMillis(80)) >= 0, "the tick is bounded below");
        assertTrue(window.motionTickRate().compareTo(Duration.ofSeconds(1)) <= 0, "the tick is bounded above");
        ticker.beat();
        assertEquals(1, redraws.get(), "a running run requests one frame per tick");

        provider.completion.accept(new RunCapability.RunCompletion(RunCapability.RunOutcome.EXITED, Optional.of(0)));
        awaitUi(uiWork, () -> window.runState() == RunMotionState.SUCCEEDED);
        ticker.beat();
        assertEquals(2, redraws.get(), "the completion effect still draws while it settles");
        now.addAndGet(600_000_000L);
        ticker.beat();
        assertEquals(2, redraws.get(), "no frame is requested once the completion effect has settled");
        ticker.sync();
    }

    @Test
    void reducedMotionTicksOncePerSecondWhileARunIsActive() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider provider = new FakeRunProvider();
        RunToolWindow window = new RunToolWindow(() -> themes, new FakeRegistry(provider), uiWork, ".",
                null, 5005, Optional.empty(), System::nanoTime, true, IconMode.ASCII);
        window.refreshConfigurations();
        assertTrue(provider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);
        window.performAction(KeyActions.RUN_EXECUTE);
        assertTrue(provider.started.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.runState() == RunMotionState.RUNNING);

        assertTrue(window.motionAnimating());
        assertEquals(Duration.ofSeconds(1), window.motionTickRate());
    }

    @Test
    void completionToolkitEffectProducesASettledRenderedFrameAtItsInjectedDuration() {
        ShellThemes themes = themes();
        RunToolWindow window = new RunToolWindow(() -> themes);
        Rect area = new Rect(0, 0, 120, 1);
        Buffer buffer = Buffer.empty(area);
        window.renderHeader(Frame.forTesting(buffer), area);
        List<Style> initialFrame = IntStream.range(0, area.width())
                .mapToObj(column -> buffer.get(column, 0).style()).toList();

        Effect effect = window.completionEffect(RunMotionState.SUCCEEDED, themes);
        effect.process(TFxDuration.fromMillis(600), buffer, area);
        List<Style> settledFrame = IntStream.range(0, area.width())
                .mapToObj(column -> buffer.get(column, 0).style()).toList();

        assertTrue(effect.done(), "the 600 ms toolkit effect must be settled at its end time");
        assertFalse(initialFrame.equals(settledFrame), "the toolkit effect must alter the rendered frame");
    }

    @Test
    void vt100AndDumbTerminalsSelectReducedMotion() {
        assertTrue(RunToolWindow.reducedMotionEnabled("full", Optional.of("vt100"), Optional.empty(), false));
        assertTrue(RunToolWindow.reducedMotionEnabled("full", Optional.of("dumb"), Optional.empty(), false));
        assertFalse(RunToolWindow.reducedMotionEnabled("full", Optional.of("xterm-256color"), Optional.empty(), false));
    }

    @Test
    void debugToolbarAttachesTheDebugCapabilityToTheStartedRunAndDetachesBeforeStop() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider runProvider = new FakeRunProvider();
        FakeDebugProvider debugProvider = new FakeDebugProvider();
        FakeRegistry registry = new FakeRegistry(runProvider);
        registry.debugProvider = debugProvider;
        FakeEditorBridge editorProvider = new FakeEditorBridge();
        registry.editorProvider = editorProvider;
        debugProvider.beforeFirstSync = editorProvider::changeBreakpointDuringReconcile;
        RunToolWindow window = new RunToolWindow(() -> themes, registry, uiWork, ".");
        window.refreshConfigurations();
        assertTrue(runProvider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);

        assertTrue(window.performAction(KeyActions.RUN_DEBUG));
        assertTrue(runProvider.started.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.statusMessage().startsWith("Debug 127.0.0.1:"));

        assertEquals(5005, runProvider.startedPort.get());
        assertEquals(5005, debugProvider.attachedPort.get());
        assertEquals(List.of(5), debugProvider.synchronizedLines.get());
        assertEquals(DebugCapability.BreakpointState.VERIFIED,
                editorProvider.sourceBreakpoints().values().iterator().next().getFirst().state());
        assertTrue(window.debugging());
        String debugRun = renderedText(window, uiWork);
        assertTrue(debugRun.contains("Resume  Pause  Over  Into  Out"), debugRun);
        assertTrue(debugRun.contains("Debugger stopped"), debugRun);
        assertEquals(KeyActions.DEBUG_RESUME,
                window.actionForMouse(MouseEvent.press(MouseButton.LEFT, 0, window.lastArea().y())));
        assertTrue(window.performAction(KeyActions.RUN_STOP));
        assertTrue(runProvider.cancelled.await(5, TimeUnit.SECONDS));
        assertTrue(debugProvider.disconnected.await(5, TimeUnit.SECONDS));
    }

    @Test
    void debuggingSendsTheStoredExceptionChoiceAndAnExceptionStopReachesTheStatusAndTheDebugWindow() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider runProvider = new FakeRunProvider();
        FakeDebugProvider debugProvider = new FakeDebugProvider();
        debugProvider.exceptionStop = Optional.of(
                new DebugCapability.ExceptionStop("java.lang.IllegalStateException", "boom"));
        FakeRegistry registry = new FakeRegistry(runProvider);
        registry.debugProvider = debugProvider;
        FakeEditorBridge editorProvider = new FakeEditorBridge();
        editorProvider.exceptionChoice = new DebugCapability.ExceptionBreakpoints(false, true);
        registry.editorProvider = editorProvider;
        RunToolWindow run = new RunToolWindow(() -> themes, registry, uiWork, ".");
        List<String> announcements = new ArrayList<>();
        run.bindStopAnnouncement(announcements::add);
        run.refreshConfigurations();
        assertTrue(runProvider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> run.configurations().size() == 2);

        assertTrue(run.performAction(KeyActions.RUN_DEBUG));
        assertTrue(runProvider.started.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> run.debugging());
        awaitUi(uiWork, () -> !announcements.isEmpty());

        assertEquals(new DebugCapability.ExceptionBreakpoints(false, true), debugProvider.exceptionRequest.get(),
                "the stored choice reaches the debug provider before the session attaches");
        assertEquals(List.of("Exception: java.lang.IllegalStateException: boom"), announcements);
        assertEquals("Exception: java.lang.IllegalStateException: boom", run.statusMessage());

        DebugToolWindow debug = new DebugToolWindow(() -> themes, run, uiWork);
        Buffer buffer = Buffer.empty(Rect.of(100, 20));
        debug.render(Frame.forTesting(buffer), Rect.of(100, 20));
        assertTrue(bufferText(buffer).contains("Exception: java.lang.IllegalStateException: boom"),
                bufferText(buffer));
        assertTrue(run.performAction(KeyActions.RUN_STOP));
        assertTrue(debugProvider.disconnected.await(5, TimeUnit.SECONDS));
    }

    @Test
    void debugToolbarReportsAnUnavailableDebugProviderBeforeStartingTheProcess() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider runProvider = new FakeRunProvider();
        RunToolWindow window = new RunToolWindow(() -> themes, new FakeRegistry(runProvider), uiWork, ".");
        window.refreshConfigurations();
        assertTrue(runProvider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> window.configurations().size() == 2);

        assertTrue(window.performAction(KeyActions.RUN_DEBUG));
        awaitUi(uiWork, () -> window.statusMessage().startsWith("Debug unavailable:"));

        assertEquals(1L, runProvider.started.getCount(), "a missing DAP provider must not start a suspended process");
        assertFalse(window.debugging());
    }

    @Test
    void debugToolWindowPilotLoadsAndExpandsInspectionAndSurvivesResize() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider runProvider = new FakeRunProvider();
        FakeDebugProvider debugProvider = new FakeDebugProvider();
        FakeRegistry registry = new FakeRegistry(runProvider);
        registry.debugProvider = debugProvider;
        RunToolWindow run = new RunToolWindow(() -> themes, registry, uiWork, ".");
        run.refreshConfigurations();
        assertTrue(runProvider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> run.configurations().size() == 2);
        assertTrue(run.performAction(KeyActions.RUN_DEBUG));
        assertTrue(runProvider.started.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> run.debugging());
        awaitUi(uiWork, () -> run.activeDebugSession() != null);

        DebugToolWindow debug = new DebugToolWindow(() -> themes, run, uiWork);
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(run, debug), new ShellState(), themes));
        try (ToolkitTestRunner test = runShell(shell, new Size(100, 28))) {
            Pilot pilot = test.pilot();
            pilot.pause(Duration.ofMillis(80));
            debug.handleKey(KeyEvent.ofChar('r'));
            awaitUi(uiWork, () -> debugProvider.firstScopesStarted.getCount() == 0);
            debugProvider.inspectionGeneration.incrementAndGet(); // resume
            debugProvider.inspectionGeneration.incrementAndGet(); // stop again before the old response returns
            debugProvider.releaseFirstScopes.countDown();
            awaitUi(uiWork, () -> debugProvider.firstScopesReturned.getCount() == 0);
            uiWork.drain();
            assertFalse(debug.statusMessage().equals("Scopes loaded"), "a response from the prior stop must be discarded");
            debug.handleKey(KeyEvent.ofChar('r'));
            awaitUi(uiWork, () -> debug.statusMessage().equals("Scopes loaded"));
            long requestsBeforePaint = debugProvider.inspectionCalls.get();
            Buffer frameBuffer = Buffer.empty(Rect.of(100, 28));
            for (int paint = 0; paint < 10; paint++) {
                debug.render(Frame.forTesting(frameBuffer), Rect.of(100, 28));
            }
            assertEquals(requestsBeforePaint, debugProvider.inspectionCalls.get(),
                    "painting must use cached inspection data without issuing debugger requests");
            String frameScreen = bufferText(frameBuffer);
            assertTrue(frameScreen.contains("Call stack"), frameScreen);
            assertTrue(frameScreen.contains("main @ /project/Main.java:12"), frameScreen);
            assertTrue(frameScreen.contains("Locals"), frameScreen);

            assertTrue(debug.handleMouse(MouseEvent.press(MouseButton.LEFT, 2, 7), Rect.of(100, 28)), frameScreen);
            awaitUi(uiWork, () -> debug.statusMessage().equals("Variables loaded"));
            Buffer inspection = Buffer.empty(Rect.of(100, 28));
            debug.render(Frame.forTesting(inspection), Rect.of(100, 28));
            String inspected = bufferText(inspection);
            assertTrue(inspected.contains("answer = 42"), inspected);
            assertTrue(inspected.contains("object = {}"), inspected);
            debug.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
            debug.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
            debug.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
            awaitUi(uiWork, () -> debug.statusMessage().equals("Variables loaded"));
            Buffer expanded = Buffer.empty(Rect.of(100, 28));
            debug.render(Frame.forTesting(expanded), Rect.of(100, 28));
            String expandedText = bufferText(expanded);
            assertTrue(expandedText.contains("answer = 42"), expandedText);
            assertTrue(expandedText.contains("nested = 7"), expandedText);
            assertTrue(expandedText.contains("cycle to reference 23"), expandedText);
            debug.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
            Buffer collapsed = Buffer.empty(Rect.of(100, 28));
            debug.render(Frame.forTesting(collapsed), Rect.of(100, 28));
            assertFalse(bufferText(collapsed).contains("nested = 7"), bufferText(collapsed));
            assertFalse(inspected.chars().anyMatch(character -> character > 0x7f), inspected);

            for (int index = 0; index < 20; index++) debug.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
            pilot.resize(70, 16);
            pilot.pause(Duration.ofMillis(50));
            Buffer resizedBuffer = Buffer.empty(Rect.of(70, 16));
            debug.render(Frame.forTesting(resizedBuffer), Rect.of(70, 16));
            String resized = bufferText(resizedBuffer);
            assertTrue(resized.contains("value19"), resized);
        }
        assertTrue(run.performAction(KeyActions.RUN_STOP));
        assertTrue(debugProvider.disconnected.await(5, TimeUnit.SECONDS));
    }

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
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(window), new ShellState(), themes));
        try (ToolkitTestRunner test = runShell(shell, new Size(100, 30))) {
            Pilot pilot = test.pilot();
            pilot.pause(Duration.ofMillis(100));
            pilot.press('4');
            pilot.pause(Duration.ofMillis(50));
            pilot.press('j');
            pilot.pause(Duration.ofMillis(50));
            assertEquals(1, window.selectedIndex());

            pilot.press(KeyCode.ENTER);
            assertTrue(provider.started.await(5, TimeUnit.SECONDS));
            pilot.pause(Duration.ofMillis(150));
            uiWork.drain();
            assertEquals("idea:Second", provider.startedConfiguration.get());
            assertEquals(RunMotionState.RUNNING, window.runState());
            assertTrue(window.consoleLines().contains("Second output"));
            assertTrue(window.statusMessage().contains("Running idea:Second"));
            Rect headerArea = pilot.findElement(RunHeaderElement.ID);
            awaitUi(uiWork, () -> window.providerLabel().equals("Run: idea (IDEA MCP)"));
            String screen = renderedShellText(shell, test, uiWork, new Size(100, 30));
            assertTrue(screen.contains("Running   idea:Second"), screen);
            assertTrue(screen.contains("Run · Running"), screen);
            assertFalse(screen.contains("Run: idea (IDEA MCP)"), screen);
            assertFalse(screen.contains("▶ Run [Enter]"), screen);
            assertFalse(screen.contains("Configurations"), screen);
            assertFalse(screen.contains("› idea:Second"), screen);
            assertTrue(screen.contains("Console"), screen);
            assertTrue(screen.contains("Second output"), screen);

            // Lifecycle-looking process output must remain ordinary console text.
            provider.output.accept("Run finished with exit code 0");
            pilot.pause(Duration.ofMillis(50));
            uiWork.drain();
            assertTrue(window.statusMessage().contains("Running idea:Second"));
            List<String> rows = screen.lines().toList();
            int stopRow = rowContaining(rows, "Stop");
            assertTrue(stopRow >= headerArea.y() && stopRow < headerArea.bottom(), screen);
            int stopColumn = rows.get(stopRow).indexOf("Stop") + 1;
            pilot.click(stopColumn, stopRow);
            assertTrue(provider.cancelled.await(5, TimeUnit.SECONDS));
            pilot.pause(Duration.ofMillis(50));
            uiWork.drain();
            assertEquals("Run cancelled", window.statusMessage());
            assertEquals(RunMotionState.STOPPED, window.runState());
            String completedScreen = renderedShellText(shell, test, uiWork, new Size(100, 30));
            assertTrue(completedScreen.contains("Stopped"), completedScreen);
            assertTrue(completedScreen.contains("idea:Second"), completedScreen);
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
        awaitUi(uiWork, () -> unavailableWindow.providerLabel().equals("Run unavailable: IDEA MCP server is disconnected"));
        assertEquals("Run unavailable: IDEA MCP server is disconnected", unavailableWindow.providerLabel());
        assertTrue(renderedText(unavailableWindow, uiWork).contains("IDEA MCP server is disconnected"));

        FakeRunProvider failingProvider = new FakeRunProvider();
        failingProvider.listResult = CapabilityResult.failed(new CapabilityFailure("Malformed response", "bad JSON"));
        FakeRegistry failing = new FakeRegistry(failingProvider);
        RunToolWindow failureWindow = new RunToolWindow(() -> themes, failing, uiWork, ".");
        failureWindow.refreshConfigurations();
        assertTrue(failingProvider.listed.await(5, TimeUnit.SECONDS));
        awaitUi(uiWork, () -> failureWindow.statusMessage().startsWith("Run configuration listing failed"));
        assertTrue(failureWindow.consoleLines().getFirst().contains("Run configuration listing failed"));
        awaitUi(uiWork, () -> failureWindow.providerLabel().equals("Run: idea (IDEA MCP)"));
        assertEquals("Run: idea (IDEA MCP)", failureWindow.providerLabel());
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
        awaitUi(uiWork, () -> window.providerLabel().equals("Run: idea (IDEA MCP)"));
        Buffer buffer = Buffer.empty(Rect.of(100, 10));
        window.render(Frame.forTesting(buffer), Rect.of(100, 10));
        String screen = bufferText(buffer);
        assertTrue(screen.contains("Run: idea (IDEA MCP)"), screen);
        assertTrue(screen.contains("Run [Enter]"), screen);
        assertTrue(screen.contains("Configurations"), screen);
        assertTrue(screen.contains("› idea:Config19"), screen);
        assertTrue(screen.contains("Console"), screen);

        // Clicking the first visible configuration must select that item, not
        // a hidden row based on the original list offset.
        String[] screenRows = screen.split("\\R");
        int firstVisibleConfigurationRow = -1;
        String firstVisibleConfiguration = null;
        for (int row = 0; row < screenRows.length; row++) {
            if (screenRows[row].contains("idea:Config")) {
                firstVisibleConfigurationRow = row;
                firstVisibleConfiguration = screenRows[row].strip().replaceFirst("^›\\s*", "").split("\\s+")[0];
                break;
            }
        }
        assertTrue(firstVisibleConfigurationRow >= 0, screen);
        int expectedIndex = -1;
        for (int index = 0; index < window.configurations().size(); index++) {
            if (window.configurations().get(index).identifier().equals(firstVisibleConfiguration)) {
                expectedIndex = index;
                break;
            }
        }
        assertTrue(expectedIndex >= 0, firstVisibleConfiguration);
        assertTrue(window.handleMouse(MouseEvent.press(MouseButton.LEFT, 5, firstVisibleConfigurationRow)));
        assertEquals(expectedIndex, window.selectedIndex());
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

    @Test
    void aSelectionThatCannotBeSavedIsReportedInTheStatusAndTheRunOutput() throws Exception {
        ShellThemes themes = themes();
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRegistry registry = new FakeRegistry(new FakeRunProvider());
        RunSelectionStore store = new RunSelectionStore(directory.resolve("config"), directory,
                (source, target, atomic) -> {
                    throw new AccessDeniedException(target.toString());
                });
        RunToolWindow window = new RunToolWindow(() -> themes, registry, uiWork, ".", store);
        window.refreshConfigurations();
        awaitUi(uiWork, () -> window.configurations().size() == 2);

        assertTrue(window.selectConfiguration("idea:Second"));

        assertEquals("idea:Second", window.selectedConfigurationLabel(), "the choice still applies to this window");
        assertTrue(window.statusMessage().startsWith("Cannot save run selection: "), window.statusMessage());
        assertTrue(window.consoleLines().contains(window.statusMessage()), window.consoleLines().toString());
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
        return renderedText(window, uiWork, Rect.of(100, 30));
    }

    private static String renderedText(RunToolWindow window, UiWorkQueue uiWork, Rect area) {
        Buffer buffer = Buffer.empty(area);
        uiWork.drain();
        window.render(Frame.forTesting(buffer), area);
        return bufferText(buffer);
    }

    private static String renderHeader(RunToolWindow window, int x, int y) {
        Rect area = new Rect(x, y, 120, 1);
        Buffer buffer = Buffer.empty(area);
        window.renderHeader(Frame.forTesting(buffer), area);
        return bufferText(buffer).stripTrailing();
    }

    private static String renderedShellText(VintelliShell shell, ToolkitTestRunner test,
            UiWorkQueue uiWork, Size size) throws Exception {
        uiWork.drain();
        FutureTask<String> render = new FutureTask<>(() -> {
            Rect area = new Rect(0, 0, size.width(), size.height());
            Buffer buffer = Buffer.empty(area);
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(shell.themes().engine());
            shell.root().render(Frame.forTesting(buffer), area, context);
            return bufferText(buffer);
        });
        test.runner().runOnRenderThread(render);
        return render.get(2, TimeUnit.SECONDS);
    }

    private static int rowContaining(List<String> rows, String text) {
        for (int row = 0; row < rows.size(); row++) {
            if (rows.get(row).contains(text)) {
                return row;
            }
        }
        return -1;
    }

    private static String bufferText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        Rect area = buffer.area();
        for (int row = area.y(); row < area.bottom(); row++) {
            for (int column = area.x(); column < area.right(); column++) {
                text.append(buffer.get(column, row).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static final class FakeRegistry implements CapabilityRegistry {
        private volatile RunCapability provider;
        private volatile DebugCapability debugProvider;
        private volatile EditorBridge editorProvider;
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

        @Override
        public CapabilitySelection<DebugCapability> debug() {
            DebugCapability current = debugProvider;
            return current == null ? unavailable(CapabilityType.DEBUG)
                    : CapabilitySelection.selected(CapabilityType.DEBUG, current);
        }

        @Override
        public CapabilitySelection<EditorBridge> editorBridge() {
            EditorBridge current = editorProvider;
            return current == null ? unavailable(CapabilityType.EDITOR_BRIDGE)
                    : CapabilitySelection.selected(CapabilityType.EDITOR_BRIDGE, current);
        }

        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { return unavailable(CapabilityType.DIAGNOSTICS); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() {
            if (unavailableReason != null) {
                return List.of(CapabilityStatus.unavailable(CapabilityType.RUN, unavailableReason));
            }
            RunCapability current = provider;
            return current == null
                    ? List.of(CapabilityStatus.unavailable(CapabilityType.RUN, "not configured"))
                    : List.of(CapabilityStatus.available(CapabilityType.RUN, current.identifier(),
                            current.provenance(), List.of()));
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }

    private static final class FakeRunProvider implements RunCapability {
        private final CountDownLatch listed = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicReference<String> startedConfiguration = new AtomicReference<>();
        private final AtomicReference<Integer> startedPort = new AtomicReference<>();
        private volatile java.util.function.Consumer<String> output = ignored -> { };
        private volatile java.util.function.Consumer<RunCompletion> completion = ignored -> { };
        private volatile CapabilityResult<List<RunConfiguration>> listResult = CapabilityResult.ok(List.of(
                new RunConfiguration("idea:First", "First launch"),
                new RunConfiguration("idea:Second", "Second launch")));

        @Override public String identifier() { return "idea"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "IDEA MCP"; }

        @Override
        public DebugSupport debugSupport(String configurationName) {
            return new DebugSupport(true, "");
        }

        @Override
        public CapabilityResult<RunHandle> startDebug(RunRequest request, int port,
                java.util.function.Consumer<String> output, java.util.function.Consumer<RunCompletion> completion) {
            startedPort.set(port);
            return start(request, output, completion);
        }

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

    private static final class FakeDebugProvider implements DebugCapability {
        private final AtomicReference<Integer> attachedPort = new AtomicReference<>();
        private final AtomicReference<List<Integer>> synchronizedLines = new AtomicReference<>(List.of());
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final CountDownLatch firstScopesStarted = new CountDownLatch(1);
        private final CountDownLatch firstScopesReturned = new CountDownLatch(1);
        private final CountDownLatch releaseFirstScopes = new CountDownLatch(1);
        private final AtomicLong inspectionGeneration = new AtomicLong();
        private final AtomicLong inspectionCalls = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicBoolean pauseFirstScopes = new java.util.concurrent.atomic.AtomicBoolean(true);
        private final java.util.concurrent.atomic.AtomicBoolean firstSync = new java.util.concurrent.atomic.AtomicBoolean(true);
        private volatile Runnable beforeFirstSync = () -> { };
        private final AtomicReference<DebugCapability.ExceptionBreakpoints> exceptionRequest = new AtomicReference<>();
        private volatile Optional<DebugCapability.ExceptionStop> exceptionStop = Optional.empty();

        @Override public CapabilityResult<DebugCapability.ExceptionBreakpoints> setExceptionBreakpoints(
                DebugCapability.ExceptionBreakpoints breakpoints) {
            exceptionRequest.set(breakpoints);
            return CapabilityResult.ok(breakpoints);
        }

        @Override public String identifier() { return "fake-dap"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "fake DAP"; }
        @Override public CapabilityResult<DebugSession> launch(DebugRequest request,
                java.util.function.Consumer<String> outputLines) {
            return CapabilityResult.failed(new CapabilityFailure("Not used", ""));
        }

        @Override
        public CapabilityResult<DebugSession> attach(AttachRequest request,
                java.util.function.Consumer<String> outputLines) {
            attachedPort.set(request.port());
            return CapabilityResult.ok(new DebugSession() {
                @Override public DebugSessionSnapshot snapshot() {
                    return new DebugSessionSnapshot("fake", request.label(), State.STOPPED,
                            List.of(new DebugThread(1, "main")), "", "breakpoint", 1,
                            new DebugFrame("main", "/project/Main.java", 12, 1, 11),
                            new DebugCapabilities(true, true, true, true, true));
                }
                @Override public long inspectionGeneration() { return inspectionGeneration.get(); }
                @Override public Optional<DebugCapability.ExceptionStop> exceptionStop() { return exceptionStop; }
                @Override public CapabilityResult<List<DebugThread>> threads() {
                    inspectionCalls.incrementAndGet();
                    return CapabilityResult.ok(List.of(new DebugThread(1, "main")));
                }
                @Override public CapabilityResult<List<DebugFrame>> stackTrace(int threadId) {
                    inspectionCalls.incrementAndGet();
                    return CapabilityResult.ok(List.of(new DebugFrame("main", "/project/Main.java", 12, 1, 11)));
                }
                @Override public CapabilityResult<Boolean> selectFrame(DebugFrame frame) {
                    return CapabilityResult.ok(true);
                }
                @Override public CapabilityResult<List<DebugScope>> scopes(int frameId) {
                    inspectionCalls.incrementAndGet();
                    if (pauseFirstScopes.compareAndSet(true, false)) {
                        firstScopesStarted.countDown();
                        try { releaseFirstScopes.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return CapabilityResult.failed(new CapabilityFailure("Interrupted", "")); }
                        firstScopesReturned.countDown();
                    }
                    return CapabilityResult.ok(List.of(new DebugScope("Locals", 22, false)));
                }
                @Override public CapabilityResult<List<DebugVariable>> variables(int reference) {
                    inspectionCalls.incrementAndGet();
                    List<DebugVariable> values = new ArrayList<>();
                    values.add(new DebugVariable("answer", "42", "int", 0, false));
                    values.add(new DebugVariable("object", "{}", "Object", 23, true));
                    for (int index = 0; index < 20; index++) {
                        values.add(new DebugVariable("value" + index, Integer.toString(index), "int", 0, false));
                    }
                    if (reference == 23) return CapabilityResult.ok(List.of(
                            new DebugVariable("nested", "7", "int", 0, false),
                            new DebugVariable("self", "{}", "Object", 23, true)));
                    return CapabilityResult.ok(List.copyOf(values));
                }
                @Override public CapabilityResult<Boolean> disconnect() {
                    disconnected.countDown();
                    return CapabilityResult.ok(true);
                }
                @Override public void cancel() { disconnected.countDown(); }
            });
        }

        @Override public List<DebugSessionSnapshot> snapshot() { return List.of(); }

        @Override
        public CapabilityResult<List<SourceBreakpoint>> setBreakpoints(String sourcePath,
                List<SourceBreakpoint> breakpoints) {
            if (firstSync.compareAndSet(true, false)) {
                beforeFirstSync.run();
            }
            synchronizedLines.set(breakpoints.stream().map(SourceBreakpoint::line).toList());
            DebugCapability.BreakpointState state = attachedPort.get() == null
                    ? DebugCapability.BreakpointState.PENDING : DebugCapability.BreakpointState.VERIFIED;
            return CapabilityResult.ok(breakpoints.stream().map(point -> new SourceBreakpoint(point.sourcePath(),
                    point.line(), state, "")).toList());
        }
    }

    private static final class FakeEditorBridge implements EditorBridge {
        private volatile long generation;
        private volatile java.util.Map<Path, List<DebugCapability.SourceBreakpoint>> currentBreakpoints =
                java.util.Map.of(Path.of("/project/Main.java"), List.of(
                        new DebugCapability.SourceBreakpoint("/project/Main.java", 4)));

        private volatile DebugCapability.ExceptionBreakpoints exceptionChoice = DebugCapability.ExceptionBreakpoints.DEFAULT;

        @Override public DebugCapability.ExceptionBreakpoints exceptionBreakpoints() { return exceptionChoice; }
        @Override public String identifier() { return "fake-editor"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "fake editor"; }
        @Override public CapabilityResult<Opened> open(OpenRequest request) {
            return CapabilityResult.ok(new Opened(request.file(), request.line()));
        }
        @Override public Optional<Path> currentFile() { return Optional.empty(); }
        @Override public java.util.Map<Path, List<DebugCapability.SourceBreakpoint>> sourceBreakpoints() {
            return currentBreakpoints;
        }
        @Override public SourceBreakpointSnapshot sourceBreakpointSnapshot() {
            return new SourceBreakpointSnapshot(generation, currentBreakpoints);
        }
        @Override public void updateSourceBreakpoints(
                java.util.Map<Path, List<DebugCapability.SourceBreakpoint>> results) {
            currentBreakpoints = results;
        }
        @Override public boolean updateSourceBreakpoints(long expectedGeneration,
                java.util.Map<Path, List<DebugCapability.SourceBreakpoint>> results) {
            if (expectedGeneration != generation) {
                return false;
            }
            currentBreakpoints = results;
            return generation == expectedGeneration;
        }
        private void changeBreakpointDuringReconcile() {
            generation++;
            currentBreakpoints = java.util.Map.of(Path.of("/project/Main.java"), List.of(
                    new DebugCapability.SourceBreakpoint("/project/Main.java", 5)));
        }
    }
}
