package dev.omatheusmesmo.vintelli.tui;

import static dev.omatheusmesmo.vintelli.tui.ToolWindowInteractionAssertions.assertActivationParity;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import org.junit.jupiter.api.Test;

class RunToolWindowInteractionContractTest {

    private static final Size VIEWPORT = new Size(90, 20);

    @Test
    void keyboardEnterAndPointerRunButtonDispatchTheSameSelectedConfiguration() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "idea:Second", KeyActions.RUN_EXECUTE, "run", 0, "Contract run output");

        assertActivationParity("Run configuration activation", expected,
                () -> newRunSession(),
                RunToolWindowInteractionContractTest::activateWithKeyboard,
                () -> newRunSession(),
                RunToolWindowInteractionContractTest::activateWithPointer);
    }

    private static ToolWindowInteractionAssertions.Session newRunSession() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRunProvider provider = new FakeRunProvider();
        FakeRegistry capabilities = new FakeRegistry(provider);
        RunToolWindow window = new RunToolWindow(() -> themes, capabilities, uiWork, ".");
        window.refreshConfigurations();
        awaitUi(uiWork, () -> window.configurations().size() == 2);

        // The compact shell stripe does not have enough vertical room to show
        // configurations; select the second item as a fixed precondition.
        assertTrue(window.performAction(KeyActions.RUN_SELECT_NEXT));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes, capabilities);
        ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, VIEWPORT);
        RunContext context = new RunContext(window, provider, uiWork);
        return new ToolWindowInteractionAssertions.Session(fixture, () -> {
            uiWork.drain();
            String selected = provider.startedConfiguration.get();
            String rendered = fixture.renderedText();
            // The provider start is observable evidence for the named run action; production dispatch has no telemetry hook.
            String action = selected == null ? "" : KeyActions.RUN_EXECUTE;
            String focused = provider.listings.get() > 1 ? "run" : "";
            String renderedSelection = rendered.contains("Contract run output") ? "Contract run output" : "";
            return new ToolWindowInteractionAssertions.Observation(
                    selected == null ? "" : selected, action, focused, 0, renderedSelection);
        }, context);
    }

    private static void activateWithKeyboard(ToolWindowInteractionAssertions.Session session) {
        ToolWindowPilotFixture fixture = session.fixture();
        RunContext context = session.context(RunContext.class);
        fixture.press('1');
        fixture.press(KeyCode.ENTER);
        awaitUi(context.uiWork(), () -> context.provider().startedConfiguration.get() != null
                && context.window().statusMessage().startsWith("Running "));
        fixture.press('r');
        awaitUi(context.uiWork(), () -> context.provider().listings.get() > 1
                && context.window().statusMessage().equals("Ready"));
    }

    private static void activateWithPointer(ToolWindowInteractionAssertions.Session session) {
        ToolWindowPilotFixture fixture = session.fixture();
        RunContext context = session.context(RunContext.class);
        fixture.press('1');
        fixture.pilot().click("tool-run", 2, 2);
        awaitUi(context.uiWork(), () -> context.provider().startedConfiguration.get() != null
                && context.window().statusMessage().startsWith("Running "));
        fixture.press('r');
        awaitUi(context.uiWork(), () -> context.provider().listings.get() > 1
                && context.window().statusMessage().equals("Ready"));
    }

    private static void awaitUi(UiWorkQueue queue, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            queue.drain();
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Run interaction", interrupted);
            }
        }
        queue.drain();
        assertTrue(condition.getAsBoolean(), "Run interaction did not reach its expected state");
    }

    private static final class FakeRegistry implements CapabilityRegistry {
        private final RunCapability provider;

        private FakeRegistry(RunCapability provider) {
            this.provider = provider;
        }

        @Override public CapabilitySelection<RunCapability> run() {
            return CapabilitySelection.selected(CapabilityType.RUN, provider);
        }
        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { return unavailable(CapabilityType.DIAGNOSTICS); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() { return List.of(); }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }

    private record RunContext(RunToolWindow window, FakeRunProvider provider, UiWorkQueue uiWork) {
    }

    private static final class FakeRunProvider implements RunCapability {
        private final AtomicInteger listings = new AtomicInteger();
        private final AtomicReference<String> startedConfiguration = new AtomicReference<>();

        @Override public String identifier() { return "idea"; }
        @Override public String provenance() { return "IDEA MCP"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }

        @Override
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            listings.incrementAndGet();
            return CapabilityResult.ok(List.of(
                    new RunConfiguration("idea:First", "First launch"),
                    new RunConfiguration("idea:Second", "Second launch")));
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output) {
            startedConfiguration.set(request.configurationName());
            output.accept("Contract run output");
            return CapabilityResult.ok(() -> { });
        }
    }
}
