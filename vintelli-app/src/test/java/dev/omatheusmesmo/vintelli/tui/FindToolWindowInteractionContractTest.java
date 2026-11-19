package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindToolWindowInteractionContractTest {

    private static final Size VIEWPORT = new Size(100, 30);
    private static final Duration WAIT = Duration.ofSeconds(3);

    @TempDir
    Path project;

    @Test
    void keyboardAndPointerOpenTheSameUsageAndKeepTheWindowOnScreen() throws Exception {
        project = project.toRealPath();
        Path caller = Files.writeString(project.resolve("Caller.java"), "helper();\n\n\nhelper();\n");
        Path other = Files.writeString(project.resolve("Other.java"), "a\nb\nc\nd\ne\nhelper();\n");
        List<NavigationCapability.Location> usages = List.of(
                new NavigationCapability.Location(caller, 0, 0),
                new NavigationCapability.Location(other, 5, 0));

        String byKeyboard;
        try (ToolWindowPilotFixture fixture = open(caller, usages)) {
            fixture.press(KeyCode.F7, KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Usages of helper in 2 files (2)"), WAIT),
                    fixture.renderedText());
            fixture.press(KeyCode.DOWN);
            fixture.press(KeyCode.DOWN);
            fixture.press(KeyCode.ENTER);
            byKeyboard = fixture.renderedText();
        }

        String byPointer;
        try (ToolWindowPilotFixture fixture = open(caller, usages)) {
            fixture.press(KeyCode.F7, KeyModifiers.ALT);
            assertTrue(fixture.await(() -> fixture.renderedText().contains("Usages of helper in 2 files (2)"), WAIT),
                    fixture.renderedText());
            Rect area = fixture.pilot().findElement("tool-find");
            int row = rowContaining(fixture.renderedText(), "6:1");
            assertTrue(row >= area.y() && row < area.bottom(), fixture.renderedText());
            fixture.pilot().click(area.x() + 2, row);
            fixture.pilot().doubleClick(area.x() + 2, row);
            byPointer = fixture.renderedText();
        }

        assertTrue(byKeyboard.contains("Other.java"), byKeyboard);
        assertTrue(byKeyboard.contains("Usages of helper in 2 files (2)"), byKeyboard);
        assertTrue(byPointer.contains("Other.java"), byPointer);
        assertEquals(selectedLine(byKeyboard), selectedLine(byPointer),
                "a double click selects the usage that Down, Down and Enter reach");
    }

    private ToolWindowPilotFixture open(Path file, List<NavigationCapability.Location> usages) throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new EditorBridge.OpenRequest(file, 0));
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(editor, new FindToolWindow(() -> themes)),
                new ShellState(), themes).withNavigationProviders(List.of(new Usages(usages))));
        return ToolWindowPilotFixture.open(shell, VIEWPORT);
    }

    private static String selectedLine(String screen) {
        return screen.lines().filter(line -> line.contains("> ")).map(line -> line.replaceAll("[▘▝▖▗▐]", " ").strip())
                .reduce((first, second) -> second).orElse("");
    }

    private static int rowContaining(String screen, String text) {
        List<String> rows = screen.lines().toList();
        for (int row = 0; row < rows.size(); row++) {
            if (rows.get(row).contains(text)) {
                return row;
            }
        }
        throw new AssertionError("no row contains " + text + ":\n" + screen);
    }

    private record Usages(List<NavigationCapability.Location> usages) implements NavigationCapability {

        @Override
        public CapabilityResult<List<Location>> definition(SourcePosition position) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "not under test"));
        }

        @Override
        public CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration) {
            return CapabilityResult.ok(usages);
        }

        @Override
        public CapabilityResult<List<DocumentSymbol>> documentSymbols(Path file) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public CapabilityResult<List<WorkspaceSymbol>> workspaceSymbols(String query) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public String identifier() {
            return "find-contract-test";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "temporary usages provider";
        }
    }
}
