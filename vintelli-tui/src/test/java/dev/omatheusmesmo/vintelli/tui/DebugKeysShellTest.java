package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugCapabilities;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugFrame;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSession;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSessionSnapshot;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugThread;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Size;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;

/**
 * The IntelliJ debugger chords through the real shell: F8, F7, Shift+F8 and F9 step while a session is suspended
 * and keep their other actions otherwise, and a terminal that holds the keyboard keeps them.
 */
class DebugKeysShellTest {

    private static final Size SIZE = new Size(120, 30);
    private static final Duration SETTLE = Duration.ofMillis(120);

    @Test
    void theStepChordsStepWhileASessionIsSuspendedEvenWhereTheProjectPaneOwnsThem() throws Exception {
        Fixture fixture = Fixture.attached(State.STOPPED, allSupported());
        try (ToolkitTestRunner test = fixture.runShell()) {
            Pilot pilot = test.pilot();
            pilot.pause(SETTLE);

            fixture.pressAndAwaitStep(pilot, KeyCode.F8, KeyModifiers.NONE, "stepOver");
            assertEquals("Step over", fixture.shell.status());
            fixture.pressAndAwaitStep(pilot, KeyCode.F7, KeyModifiers.NONE, "stepInto");
            fixture.pressAndAwaitStep(pilot, KeyCode.F8, KeyModifiers.SHIFT, "stepOut");
            fixture.pressAndAwaitStep(pilot, KeyCode.F9, KeyModifiers.NONE, "resume");

            assertEquals(List.of("stepOver", "stepInto", "stepOut", "resume"), fixture.session.calls);
            assertFalse(fixture.shell.status().startsWith("New "), fixture.shell.status());
        }
    }

    @Test
    void withoutASessionF7KeepsOpeningNewFileAndF8StepsNothing() throws Exception {
        Fixture fixture = Fixture.withoutSession();
        try (ToolkitTestRunner test = fixture.runShell()) {
            Pilot pilot = test.pilot();
            pilot.pause(SETTLE);

            pilot.press(KeyCode.F8);
            pilot.pause(SETTLE);
            assertFalse(fixture.shell.status().startsWith("Step"), fixture.shell.status());

            pilot.press(KeyCode.F7);
            pilot.pause(SETTLE);
            assertTrue(fixture.shell.status().startsWith("New file:"), fixture.shell.status());
        }
    }

    @Test
    void whileTheSessionRunsF7KeepsOpeningNewFileAndNothingSteps() throws Exception {
        Fixture fixture = Fixture.attached(State.RUNNING, allSupported());
        try (ToolkitTestRunner test = fixture.runShell()) {
            Pilot pilot = test.pilot();
            pilot.pause(SETTLE);

            pilot.press(KeyCode.F8);
            pilot.pause(SETTLE);
            assertEquals(List.of(), fixture.session.calls);

            pilot.press(KeyCode.F9);
            pilot.pause(SETTLE);
            assertTrue(fixture.shell.status().startsWith("New directory:"), fixture.shell.status());
            assertEquals(List.of(), fixture.session.calls);
            pilot.press(KeyCode.ESCAPE);
            pilot.pause(SETTLE);

            pilot.press(KeyCode.F7);
            pilot.pause(SETTLE);
            assertTrue(fixture.shell.status().startsWith("New file:"), fixture.shell.status());
        }
    }

    @Test
    void aTerminalThatHoldsTheKeyboardKeepsTheStepChords() throws Exception {
        Fixture fixture = Fixture.attached(State.STOPPED, allSupported());
        try (ToolkitTestRunner test = fixture.runShell()) {
            Pilot pilot = test.pilot();
            pilot.pause(SETTLE);
            pilot.press('2', KeyModifiers.ALT);
            pilot.pause(SETTLE);
            assertEquals("Tool window: Terminal", fixture.shell.status());

            pilot.press(KeyCode.F8);
            pilot.press(KeyCode.F7);
            pilot.press(KeyCode.F9);
            pilot.press(KeyCode.F8, KeyModifiers.SHIFT);
            pilot.pause(SETTLE);

            assertEquals(List.of(), fixture.session.calls);
        }
    }

    @Test
    void anUnsupportedStepReportsTheAdapterReasonAndCallsNothing() throws Exception {
        Fixture fixture = Fixture.attached(State.STOPPED, new DebugCapabilities(true, true, true, false, true));
        try (ToolkitTestRunner test = fixture.runShell()) {
            Pilot pilot = test.pilot();
            pilot.pause(SETTLE);

            pilot.press(KeyCode.F7);
            pilot.pause(SETTLE);

            assertEquals("Step into unavailable: unsupported by this adapter", fixture.shell.status());
            assertEquals(List.of(), fixture.session.calls);
        }
    }

    @Test
    void shiftF9StartsDebugWithoutASessionAndDoesNotCreateADirectory() throws Exception {
        Fixture fixture = Fixture.withoutSession();
        try (ToolkitTestRunner test = fixture.runShell()) {
            Pilot pilot = test.pilot();
            pilot.pause(SETTLE);

            pilot.press(KeyCode.F9, KeyModifiers.SHIFT);
            assertTrue(fixture.runProvider.started.await(5, TimeUnit.SECONDS));
            awaitUi(fixture.uiWork, () -> fixture.run.debugging() && fixture.run.activeDebugSession() != null);

            assertFalse(fixture.shell.status().startsWith("New directory"), fixture.shell.status());
            assertTrue(fixture.debugProvider.attached.await(5, TimeUnit.SECONDS));
        }
    }

    private static DebugCapabilities allSupported() {
        return new DebugCapabilities(true, true, true, true, true);
    }

    private static void awaitUi(UiWorkQueue queue, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            queue.drain();
            Thread.sleep(10);
        }
        queue.drain();
        assertTrue(condition.getAsBoolean());
    }

    private static final class Fixture {
        private final UiWorkQueue uiWork = new UiWorkQueue();
        private final FakeRunProvider runProvider = new FakeRunProvider();
        private final FakeDebugProvider debugProvider = new FakeDebugProvider();
        private final RecordingSession session;
        private final RunToolWindow run;
        private final VintelliShell shell;

        private Fixture(State state, DebugCapabilities capabilities) throws Exception {
            session = new RecordingSession(state, capabilities);
            debugProvider.session = session;
            ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
            FakeRegistry registry = new FakeRegistry(runProvider, debugProvider);
            run = new RunToolWindow(() -> themes, registry, uiWork, ".");
            run.refreshConfigurations();
            assertTrue(runProvider.listed.await(5, TimeUnit.SECONDS));
            awaitUi(uiWork, () -> run.configurations().size() == 1);
            shell = new VintelliShell(ShellConfig.of(
                    new ToolWindowRegistry(List.of(run, new TerminalToolWindow(() -> themes))).toolWindows(),
                    new ShellState(), themes).withFileOperations(new NoopFileOperations()));
        }

        static Fixture attached(State state, DebugCapabilities capabilities) throws Exception {
            Fixture fixture = new Fixture(state, capabilities);
            assertTrue(fixture.run.performAction(KeyActions.RUN_DEBUG));
            assertTrue(fixture.runProvider.started.await(5, TimeUnit.SECONDS));
            awaitUi(fixture.uiWork, () -> fixture.run.debugging() && fixture.run.activeDebugSession() != null);
            return fixture;
        }

        static Fixture withoutSession() throws Exception {
            return new Fixture(State.STOPPED, allSupported());
        }

        ToolkitTestRunner runShell() throws Exception {
            ToolkitTestRunner test = ToolkitTestRunner.runTest(shell::root, SIZE);
            test.runner().styleEngine(shell.themes().engine());
            test.runner().eventRouter().addGlobalHandler(shell);
            shell.bindQuit(test.runner()::quit);
            return test;
        }

        void pressAndAwaitStep(Pilot pilot, KeyCode key, KeyModifiers modifiers, String call) throws Exception {
            int before = session.calls.size();
            pilot.press(key, modifiers);
            pilot.pause(SETTLE);
            awaitUi(uiWork, () -> session.calls.size() == before + 1 && run.statusMessage().endsWith("requested"));
            assertEquals(call, session.calls.get(before));
        }
    }

    private enum State { STOPPED, RUNNING }

    private static final class NoopFileOperations implements ProjectFileOperations {
        @Override public CapabilityResult<Path> createFile(Path directory, String name) {
            return CapabilityResult.ok(directory.resolve(name));
        }
        @Override public CapabilityResult<Path> createDirectory(Path directory, String name) {
            return CapabilityResult.ok(directory.resolve(name));
        }
        @Override public CapabilityResult<Path> rename(Path entry, String name) { return CapabilityResult.ok(entry); }
        @Override public CapabilityResult<Path> delete(Path entry) { return CapabilityResult.ok(entry); }
    }

    private static final class RecordingSession implements DebugSession {
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final DebugCapability.State state;
        private final DebugCapabilities capabilities;

        private RecordingSession(State state, DebugCapabilities capabilities) {
            this.state = state == State.STOPPED ? DebugCapability.State.STOPPED : DebugCapability.State.RUNNING;
            this.capabilities = capabilities;
        }

        @Override public DebugSessionSnapshot snapshot() {
            return new DebugSessionSnapshot("fake", "First launch", state, List.of(new DebugThread(1, "main")), "",
                    "breakpoint", 1, new DebugFrame("main", "/project/Main.java", 12, 1, 11), capabilities);
        }
        @Override public CapabilityResult<List<DebugThread>> threads() {
            return CapabilityResult.ok(List.of(new DebugThread(1, "main")));
        }
        @Override public CapabilityResult<Boolean> resume() { return record("resume"); }
        @Override public CapabilityResult<Boolean> stepOver() { return record("stepOver"); }
        @Override public CapabilityResult<Boolean> stepInto() { return record("stepInto"); }
        @Override public CapabilityResult<Boolean> stepOut() { return record("stepOut"); }
        @Override public CapabilityResult<Boolean> disconnect() { return CapabilityResult.ok(true); }
        @Override public void cancel() { }

        private CapabilityResult<Boolean> record(String call) {
            calls.add(call);
            return CapabilityResult.ok(true);
        }
    }

    private static final class FakeDebugProvider implements DebugCapability {
        private final CountDownLatch attached = new CountDownLatch(1);
        private volatile DebugSession session;

        @Override public String identifier() { return "fake-dap"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "fake DAP"; }
        @Override public CapabilityResult<DebugSession> launch(DebugRequest request, Consumer<String> outputLines) {
            return CapabilityResult.failed(new CapabilityFailure("Not used", ""));
        }
        @Override public CapabilityResult<DebugSession> attach(AttachRequest request, Consumer<String> outputLines) {
            attached.countDown();
            return CapabilityResult.ok(session);
        }
        @Override public List<DebugSessionSnapshot> snapshot() { return List.of(); }
    }

    private static final class FakeRunProvider implements RunCapability {
        private final CountDownLatch listed = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);

        @Override public String identifier() { return "idea"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "IDEA MCP"; }
        @Override public DebugSupport debugSupport(String configurationName) { return new DebugSupport(true, ""); }

        @Override
        public CapabilityResult<RunHandle> startDebug(RunRequest request, int port, Consumer<String> output,
                Consumer<RunCompletion> completion) {
            return start(request, output, completion);
        }

        @Override
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            listed.countDown();
            return CapabilityResult.ok(List.of(new RunConfiguration("idea:First", "First launch")));
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output) {
            return start(request, output, ignored -> { });
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output,
                Consumer<RunCompletion> completion) {
            started.countDown();
            return CapabilityResult.ok(() -> completion.accept(
                    new RunCompletion(RunOutcome.CANCELLED, java.util.Optional.empty())));
        }
    }

    private static final class FakeRegistry implements CapabilityRegistry {
        private final RunCapability run;
        private final DebugCapability debug;

        private FakeRegistry(RunCapability run, DebugCapability debug) {
            this.run = run;
            this.debug = debug;
        }

        @Override public CapabilitySelection<RunCapability> run() {
            return CapabilitySelection.selected(CapabilityType.RUN, run);
        }
        @Override public CapabilitySelection<DebugCapability> debug() {
            return CapabilitySelection.selected(CapabilityType.DEBUG, debug);
        }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { return unavailable(CapabilityType.DIAGNOSTICS); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() {
            return List.of(CapabilityStatus.available(CapabilityType.RUN, run.identifier(), run.provenance(),
                    List.of()));
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }
}
