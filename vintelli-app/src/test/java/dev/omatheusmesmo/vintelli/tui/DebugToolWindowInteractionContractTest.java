package dev.omatheusmesmo.vintelli.tui;

import static dev.omatheusmesmo.vintelli.tui.ToolWindowInteractionAssertions.assertActivationParity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugFrame;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugScope;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSession;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSessionSnapshot;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugThread;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugVariable;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.tui.RunToolWindowInteractionContractTest.FakeRegistry;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

class DebugToolWindowInteractionContractTest {

    private static final Size VIEWPORT = new Size(100, 24);
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void keyboardAndPointerExpandTheSameSelectedScope() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "answer = 42 : int", "debug.inspect.variables:22", "tool-debug", 0, "answer = 42 : int");

        for (boolean unicode : new boolean[] { false, true }) {
            assertActivationParity("Debug scope inspection (Unicode=" + unicode + ")", expected,
                    () -> newSession(unicode),
                    DebugToolWindowInteractionContractTest::expandWithKeyboard,
                    () -> newSession(unicode),
                    DebugToolWindowInteractionContractTest::expandWithPointer);
        }
    }

    private ToolWindowInteractionAssertions.Session newSession(boolean unicode) throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        RecordingRunProvider runProvider = new RecordingRunProvider();
        RecordingDebugProvider debugProvider = new RecordingDebugProvider();
        FakeRegistry registry = new FakeRegistry(runProvider, debugProvider);
        RunToolWindow run = new RunToolWindow(() -> themes, registry, uiWork, ".");
        DebugToolWindow debug = new DebugToolWindow(() -> themes, run, uiWork);
        run.refreshConfigurations();
        awaitUi(uiWork, () -> run.debugAvailable());
        assertTrue(run.performAction(KeyActions.RUN_DEBUG), run.statusMessage());
        awaitUi(uiWork, () -> run.debugging() && run.activeDebugSession() != null);

        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(run, debug), new ShellState(), themes)
                .withCapabilityRegistry(registry));
        shell.useUnicodeProjectTree(unicode);
        ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, VIEWPORT);
        return new ToolWindowInteractionAssertions.Session(fixture,
                () -> observe(fixture, debug, debugProvider),
                new DebugContext(run, debug, debugProvider, uiWork));
    }

    private static void focusAndLoad(ToolWindowInteractionAssertions.Session session) {
        ToolWindowPilotFixture fixture = session.fixture();
        DebugContext context = session.context(DebugContext.class);
        fixture.press('3', KeyModifiers.ALT);
        assertEquals("tool-debug", fixture.testRunner().runner().focusManager().focusedId());
        fixture.press('r');
        awaitUi(context.uiWork(), () -> context.window().statusMessage().equals("Scopes loaded"));
    }

    private static void expandWithKeyboard(ToolWindowInteractionAssertions.Session session) {
        focusAndLoad(session);
        ToolWindowPilotFixture fixture = session.fixture();
        DebugContext context = session.context(DebugContext.class);
        fixture.press(KeyCode.DOWN);
        fixture.press(KeyCode.DOWN);
        fixture.press(KeyCode.ENTER);
        awaitUi(context.uiWork(), () -> context.window().statusMessage().equals("Variables loaded"));
        fixture.press(KeyCode.DOWN);
    }

    private static void expandWithPointer(ToolWindowInteractionAssertions.Session session) {
        focusAndLoad(session);
        ToolWindowPilotFixture fixture = session.fixture();
        DebugContext context = session.context(DebugContext.class);
        Rect area = fixture.pilot().findElement("tool-debug");
        int scopeRow = rowContaining(fixture.renderedText(), "[+] Locals");
        assertTrue(scopeRow >= area.y() && scopeRow < area.bottom(), fixture.renderedText());
        fixture.pilot().click(area.x() + 2, scopeRow);
        awaitUi(context.uiWork(), () -> context.window().statusMessage().equals("Variables loaded"));
        fixture.scrollDown(area.x() + 2, area.bottom() - 1);
    }

    private static ToolWindowInteractionAssertions.Observation observe(ToolWindowPilotFixture fixture,
            DebugToolWindow window, RecordingDebugProvider provider) {
        String rendered = fixture.renderedText();
        Rect area = fixture.pilot().findElement("tool-debug");
        List<String> contentRows = rendered.lines().map(line -> line.substring(
                Math.min(area.x(), line.length()), Math.min(area.right() - 1, line.length()))).toList();
        String selectedValue = contentRows.stream().filter(line -> line.contains("answer = 42 : int"))
                .findFirst().map(line -> line.substring(line.indexOf("answer = 42 : int")).strip()).orElse("");
        String selectedRow = contentRows.stream().filter(line -> line.contains("> ") && line.contains("answer = 42 : int"))
                .findFirst().map(line -> line.substring(line.lastIndexOf("> ") + 2).strip()).orElse("");
        int requestedReference = provider.variableReference.get();
        String action = requestedReference > 0 ? "debug.inspect.variables:" + requestedReference : "";
        return new ToolWindowInteractionAssertions.Observation(selectedValue, action,
                fixture.testRunner().runner().focusManager().focusedId(), 0, selectedRow,
                "Debug status=" + window.statusMessage() + "\nRendered buffer:\n" + rendered);
    }

    private static int rowContaining(String screen, String token) {
        List<String> rows = screen.lines().toList();
        for (int row = 0; row < rows.size(); row++) {
            if (rows.get(row).contains(token)) return row;
        }
        throw new AssertionError("No rendered row contains " + token + ":\n" + screen);
    }

    private static void awaitUi(UiWorkQueue uiWork, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            uiWork.drain();
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for debugger inspection", interrupted);
            }
        }
        uiWork.drain();
        assertTrue(condition.getAsBoolean(), "Debugger inspection did not reach its expected state");
    }

    private record DebugContext(RunToolWindow run, DebugToolWindow window, RecordingDebugProvider provider,
            UiWorkQueue uiWork) implements AutoCloseable {
        @Override public void close() {
            window.close();
            run.performAction(KeyActions.RUN_STOP);
        }
    }

    private static final class RecordingRunProvider implements RunCapability {
        @Override public String identifier() { return "interaction-run"; }
        @Override public String provenance() { return "debug interaction twin"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public DebugSupport debugSupport(String configurationName) { return new DebugSupport(true, ""); }
        @Override public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            return CapabilityResult.ok(List.of(new RunConfiguration("interaction:debug", "Debug interaction")));
        }
        @Override public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output) {
            return CapabilityResult.failed(new CapabilityFailure("Run is not used by this interaction", ""));
        }
        @Override public CapabilityResult<RunHandle> startDebug(RunRequest request, int port,
                Consumer<String> output, Consumer<RunCompletion> completed) {
            output.accept("Debugger attached");
            return CapabilityResult.ok(() -> { });
        }
    }

    private static final class RecordingDebugProvider implements DebugCapability {
        private final AtomicInteger variableRequests = new AtomicInteger();
        private final AtomicInteger variableReference = new AtomicInteger(-1);
        private volatile DebugSessionSnapshot snapshot;

        @Override public String identifier() { return "interaction-dap"; }
        @Override public String provenance() { return "debug interaction twin"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public CapabilityResult<DebugSession> launch(DebugRequest request, Consumer<String> output) {
            return CapabilityResult.failed(new CapabilityFailure("Launch is not used by this interaction", ""));
        }
        @Override public CapabilityResult<DebugSession> attach(AttachRequest request, Consumer<String> output) {
            snapshot = new DebugSessionSnapshot("interaction-debug-session", request.label(), State.STOPPED,
                    List.of(new DebugThread(1, "main")), "", "breakpoint", 1, null,
                    new DebugCapabilities(true, true, true, true, true));
            return CapabilityResult.ok(new DebugSession() {
                @Override public DebugSessionSnapshot snapshot() { return RecordingDebugProvider.this.snapshot; }
                @Override public CapabilityResult<List<DebugThread>> threads() {
                    return CapabilityResult.ok(List.of(new DebugThread(1, "main")));
                }
                @Override public CapabilityResult<List<DebugFrame>> stackTrace(int threadId) {
                    return CapabilityResult.ok(List.of(new DebugFrame("main", "/project/Main.java", 12, 1, 11)));
                }
                @Override public CapabilityResult<Boolean> selectFrame(DebugFrame frame, long generation) {
                    snapshot = new DebugSessionSnapshot("interaction-debug-session", request.label(), State.STOPPED,
                            List.of(new DebugThread(1, "main")), "", "breakpoint", 1, frame,
                            new DebugCapabilities(true, true, true, true, true));
                    return CapabilityResult.ok(true);
                }
                @Override public CapabilityResult<List<DebugScope>> scopes(int frameId) {
                    return CapabilityResult.ok(List.of(new DebugScope("Locals", 22, false)));
                }
                @Override public CapabilityResult<List<DebugVariable>> variables(int reference) {
                    variableRequests.incrementAndGet();
                    variableReference.set(reference);
                    if (reference != 22) {
                        return CapabilityResult.failed(new CapabilityFailure("Unexpected variables reference", ""));
                    }
                    return CapabilityResult.ok(List.of(new DebugVariable("answer", "42", "int", 0, false)));
                }
                @Override public long inspectionGeneration() { return 1; }
                @Override public CapabilityResult<Boolean> disconnect() { return CapabilityResult.ok(true); }
                @Override public void cancel() { }
            });
        }
        @Override public List<DebugSessionSnapshot> snapshot() {
            return snapshot == null ? List.of() : List.of(snapshot);
        }
    }
}
