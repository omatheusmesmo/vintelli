package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import org.junit.jupiter.api.Test;

class StatusBarTest {

    private static final Path ROOT = Path.of("work", "vintelli");

    @Test
    void compactsTheShellMessageToTheKnownStates() {
        assertEquals("Ready", StatusBar.compactShell("Vintelli ready | IDEA MCP: disconnected"));
        assertEquals("Ready", StatusBar.compactShell("Project files loaded"));
        assertEquals("Loading", StatusBar.compactShell("Loading project files..."));
        assertEquals("No files", StatusBar.compactShell("No project files found"));
        assertEquals("Project!", StatusBar.compactShell("Project files unavailable"));
        assertEquals("Tool window: Run", StatusBar.compactShell("Tool window: Run"));
    }

    @Test
    void truncatesAnUnknownShellMessageToEightCells() {
        assertEquals("Saved t…", StatusBar.compactShell("Saved to disk"));
    }

    @Test
    void drawsTheShellWidgetAloneWhileInputIsBlocking() {
        StatusBar bar = new StatusBar(() -> "Vintelli ready | IDEA MCP: disconnected", () -> true, null, null,
                List.of());

        List<StatusBarWidget> widgets = bar.widgets();

        assertEquals(List.of(new StatusBarWidget("shell", 0, "Vintelli ready", "Ready")), widgets);
    }

    @Test
    void breadcrumbSegmentsStartAtTheRootAndExtendByOnePartEach() {
        List<StatusBar.Breadcrumb> segments = StatusBar.pathBreadcrumbs(ROOT, Path.of("src", "Main.java"));

        assertEquals(List.of("vintelli", "src", "Main.java"), labels(segments));
        assertEquals(ROOT, segments.get(0).path());
        assertEquals(ROOT.resolve("src"), segments.get(1).path());
        assertEquals(ROOT.resolve("src").resolve("Main.java"), segments.get(2).path());
        assertTrue(segments.stream().allMatch(segment -> segment.line() == -1));
    }

    @Test
    void symbolBreadcrumbsKeepOnlyTypesAndCallablesAndOpenTheirLine() {
        Path file = ROOT.resolve("Main.java");
        List<NavigationCapability.DocumentSymbol> containing = List.of(
                symbol("Main", 5, 3),
                symbol("field", 8, 4),
                symbol("run", 6, 9));

        List<StatusBar.Breadcrumb> segments = StatusBar.symbolBreadcrumbs(file, containing);

        assertEquals(List.of(new StatusBar.Breadcrumb("Main", file, 3), new StatusBar.Breadcrumb("run", file, 9)),
                segments);
    }

    @Test
    void capabilityWidgetNamesTheProviderAndCompactsItToSevenCells() {
        CapabilityStatus status = CapabilityStatus.available(CapabilityType.RUN, "local", "config", List.of());

        StatusBarWidget widget = StatusBar.capabilityWidget(status, 10);

        assertEquals(new StatusBarWidget("capability-run", 10, "Run: local (config)", "R:local"), widget);
    }

    @Test
    void capabilityWidgetShowsAnUnavailableReasonCompactedToTwelveCells() {
        CapabilityStatus status = CapabilityStatus.unavailable(CapabilityType.TERMINAL, "pty is missing");

        StatusBarWidget widget = StatusBar.capabilityWidget(status, 11);

        assertEquals("Terminal: unavailable: pty is missing", widget.text());
        assertEquals("T:!pty is miss…", widget.compactText());
    }

    private static List<String> labels(List<StatusBar.Breadcrumb> segments) {
        return segments.stream().map(StatusBar.Breadcrumb::label).toList();
    }

    private static NavigationCapability.DocumentSymbol symbol(String name, int kind, int line) {
        return new NavigationCapability.DocumentSymbol(name, kind, line, 0, line, 0, line, 0, line, 0, List.of());
    }
}
