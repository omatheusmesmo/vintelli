package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.tui.RunToolWindowInteractionContractTest.FakeDebugCapability;
import dev.omatheusmesmo.vintelli.tui.RunToolWindowInteractionContractTest.FakeRegistry;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

class DebugLaunchInteractionTest {
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
