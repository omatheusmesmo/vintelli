package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.tui.RunToolWindowInteractionContractTest.FakeRegistry;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunConfigurationPickerInteractionTest {

    @TempDir
    Path directory;

    @Test
    void paletteAndToolbarChooseTheSameConfigurationWithoutStartingIt() throws Exception {
        for (boolean pointer : List.of(false, true)) {
            Path project = Files.createDirectory(directory.resolve(pointer ? "pointer" : "keyboard"));
            RunSelectionStore store = new RunSelectionStore(directory.resolve("config"), project);
            SelectionProvider provider = new SelectionProvider();
            RunToolWindow window = window(project, store, provider);
            try (ToolWindowPilotFixture fixture = fixture(window)) {
                String previousFocus = fixture.testRunner().runner().focusManager().focusedId();
                if (pointer) {
                    click(fixture, "maven:quarkus:dev", 0);
                } else {
                    openFromPalette(fixture);
                }
                assertTrue(fixture.renderedText().contains("maven:compile"), fixture.renderedText());
                assertEquals(RunConfigurationChooser.ID, fixture.testRunner().runner().focusManager().focusedId());
                if (pointer) click(fixture, "maven:compile", 1);
                else {
                    fixture.press(KeyCode.DOWN);
                    fixture.press(KeyCode.ENTER);
                }
                assertEquals("maven:compile", window.selectedConfigurationLabel());
                assertEquals("maven:compile", store.load().orElseThrow());
                assertEquals(previousFocus, fixture.testRunner().runner().focusManager().focusedId());
                assertTrue(fixture.renderedText().lines().findFirst().orElseThrow().contains("maven:compile"), fixture.renderedText());
                assertEquals(0, provider.starts.get());
            }
        }
    }

    @Test
    void runWindowChoiceUpdatesTheToolbarAndSurvivesANewWindow() throws Exception {
        RunSelectionStore store = new RunSelectionStore(directory.resolve("config"), directory);
        store.save("removed-configuration");
        SelectionProvider provider = new SelectionProvider();
        RunToolWindow window = window(directory, store, provider);
        assertEquals("maven:quarkus:dev", window.selectedConfigurationLabel());
        try (ToolWindowPilotFixture fixture = fixture(window)) {
            fixture.press('4', KeyModifiers.ALT);
            fixture.press(KeyCode.DOWN);
            assertEquals("maven:compile", window.selectedConfigurationLabel());
            assertTrue(fixture.renderedText().lines().findFirst().orElseThrow().contains("maven:compile"), fixture.renderedText());
        }
        RunToolWindow restored = window(directory, new RunSelectionStore(directory.resolve("config"), directory), provider);
        assertEquals("maven:compile", restored.selectedConfigurationLabel());
        assertEquals(0, provider.starts.get());
    }

    @Test
    void scrolledChooserUsesTheLatestGeometryAndOutsideClicksDoNotSelect() throws Exception {
        RunSelectionStore store = new RunSelectionStore(directory.resolve("config"), directory);
        SelectionProvider provider = new SelectionProvider();
        RunToolWindow window = window(directory, store, provider);
        try (ToolWindowPilotFixture fixture = fixture(window)) {
            openFromPalette(fixture);
            fixture.pilot().click(1, 0);
            assertEquals("maven:quarkus:dev", window.selectedConfigurationLabel());
            openFromPalette(fixture);
            for (int index = 0; index < 15; index++) fixture.press(KeyCode.DOWN);
            fixture.resize(90, 16);
            click(fixture, "configuration-13", 1);
            assertEquals("configuration-13", window.selectedConfigurationLabel());
            assertEquals("configuration-13", store.load().orElseThrow());
            assertEquals(0, provider.starts.get());
        }
    }

    private static void openFromPalette(ToolWindowPilotFixture fixture) {
        fixture.press('p', KeyModifiers.CTRL);
        for (char character : "Choose Run Configuration".toCharArray()) fixture.pilot().press(character);
        fixture.press(KeyCode.ENTER);
    }

    private static void click(ToolWindowPilotFixture fixture, String label, int firstRow) {
        String[] rows = fixture.renderedText().split("\n");
        for (int row = firstRow; row < rows.length; row++) {
            int column = rows[row].indexOf(label);
            if (column >= 0) {
                fixture.pilot().click(column + 1, row);
                fixture.pilot().pause(Duration.ofMillis(60));
                return;
            }
        }
        throw new AssertionError("Missing configuration: " + label + "\n" + fixture.renderedText());
    }

    private static RunToolWindow window(Path project, RunSelectionStore store, SelectionProvider provider) throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue queue = new UiWorkQueue();
        RunToolWindow window = new RunToolWindow(() -> themes, new FakeRegistry(provider), queue, project.toString(), store);
        window.refreshConfigurations();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (window.configurations().isEmpty() && System.nanoTime() < deadline) {
            queue.drain();
            Thread.sleep(10);
        }
        assertTrue(!window.configurations().isEmpty());
        return window;
    }

    private static ToolWindowPilotFixture fixture(RunToolWindow window) throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(window), new ShellState(), themes));
        return ToolWindowPilotFixture.open(shell, new Size(140, 30));
    }

    private static final class SelectionProvider implements RunCapability {
        private final AtomicInteger starts = new AtomicInteger();
        public String identifier() { return "test"; }
        public String provenance() { return "test configurations"; }
        public ProviderHealth health() { return ProviderHealth.available(); }
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            List<RunConfiguration> configurations = new ArrayList<>(List.of(
                    new RunConfiguration("maven:quarkus:dev", "Start"), new RunConfiguration("maven:compile", "Compile")));
            configurations.addAll(IntStream.range(0, 20).mapToObj(index -> new RunConfiguration("configuration-" + index, "")).toList());
            return CapabilityResult.ok(configurations);
        }
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output) {
            starts.incrementAndGet();
            return CapabilityResult.ok(() -> { });
        }
    }
}
