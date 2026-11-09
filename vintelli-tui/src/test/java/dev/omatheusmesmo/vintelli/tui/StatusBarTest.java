package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import dev.omatheusmesmo.vintelli.tui.StatusBarLayout.Segment;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import org.junit.jupiter.api.Test;

class StatusBarTest {

    private static final Path ROOT = Path.of("work", "vintelli");
    private static final StatusBar.Glyphs UNICODE = new StatusBar.Glyphs(true, IconMode.NERD);
    private static final StatusBar.Glyphs ASCII = new StatusBar.Glyphs(false, IconMode.ASCII);

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
    void drawsTheShellMessageAloneAndNoBreadcrumbWhileInputIsBlocking() {
        StatusBar bar = new StatusBar(() -> "Vintelli ready | IDEA MCP: disconnected", () -> true, null, null,
                List.of(), UNICODE, () -> StatusBar.Navigation.NONE);

        assertEquals(List.of(), bar.left());
        assertEquals(List.of(new Segment(StatusBarLayout.MESSAGE_ID, 80, "Vintelli ready", "Ready")), bar.right());
    }

    @Test
    void leftSegmentsRankTheFileAboveItsAncestorsAndBelowNothingElse() {
        StatusBar bar = new StatusBar(() -> "Ready", () -> false, null, null, List.of(), UNICODE,
                () -> new StatusBar.Navigation(List.of(
                        new StatusBar.Breadcrumb("vintelli", ROOT, -1),
                        new StatusBar.Breadcrumb("src", ROOT.resolve("src"), -1),
                        new StatusBar.Breadcrumb("Main.java", ROOT.resolve("src").resolve("Main.java"), -1),
                        new StatusBar.Breadcrumb("run", ROOT.resolve("src").resolve("Main.java"), 9)),
                        3, false));

        List<Segment> left = bar.left();

        assertEquals(List.of("crumb-0", "crumb-1", "crumb-2", "crumb-3"), ids(left));
        assertEquals(10, left.get(0).priority());
        assertEquals(11, left.get(1).priority());
        assertEquals(90, left.get(2).priority());
        assertEquals(60, left.get(3).priority());
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
    void providerTextNamesTheProviderAndTheUnavailableReason() {
        CapabilityStatus available = CapabilityStatus.available(CapabilityType.RUN, "local", "config", List.of());
        CapabilityStatus unavailable = CapabilityStatus.unavailable(CapabilityType.TERMINAL, "pty is missing");

        assertEquals("Run: local (config)", StatusBar.providerText(available));
        assertEquals("Terminal: unavailable: pty is missing", StatusBar.providerText(unavailable));
    }

    @Test
    void theIndicatorAppearsOnlyWhileACapabilityHasNoHealthyProvider() {
        StatusBar healthy = bar(UNICODE, List.of(
                CapabilityStatus.available(CapabilityType.RUN, "local", "config", List.of())));
        StatusBar degraded = bar(UNICODE, List.of(
                CapabilityStatus.available(CapabilityType.RUN, "local", "config", List.of()),
                CapabilityStatus.unavailable(CapabilityType.TERMINAL, "pty is missing")));

        assertFalse(ids(healthy.right()).contains(StatusBar.PROVIDERS_ID), ids(healthy.right()).toString());
        assertEquals(StatusBar.PROVIDERS_ID, degraded.right().get(0).id());
        assertEquals("⚠ 1 provider degraded", degraded.right().get(0).text());
        assertEquals("⚠ 1 degraded", degraded.right().get(0).shortText());
    }

    @Test
    void theIndicatorPluralizesAndUsesAsciiWithoutUnicode() {
        StatusBar bar = bar(ASCII, List.of(
                CapabilityStatus.unavailable(CapabilityType.TERMINAL, "pty is missing"),
                CapabilityStatus.unavailable(CapabilityType.RUN, "no runner")));

        assertEquals("! 2 providers degraded", bar.right().get(0).text());
    }

    @Test
    void providerLinesListEveryCapabilityWithItsContributedWidgets() {
        CapabilityStatus run = CapabilityStatus.available(CapabilityType.RUN, "local", "config", List.of());
        CapabilityStatus vcs = CapabilityStatus.available(CapabilityType.VERSION_CONTROL, "git", "repo", List.of());
        FixedStatusRegistry registry = new FixedStatusRegistry(List.of(
                new CapabilityStatusWidgets(run, List.of()),
                new CapabilityStatusWidgets(vcs, List.of(new StatusBarWidget("vcs-branch", 7, "Branch: main", "x")))));
        StatusBar bar = new StatusBar(() -> "Ready", () -> false, registry, null, List.of(), ASCII,
                () -> StatusBar.Navigation.NONE);

        assertEquals(List.of("Run: local (config)", "VCS: git (repo)", "Branch: main"), bar.providerLines());
    }

    @Test
    void theReadOnlyLockUsesTheNerdGlyphOnlyWithTheNerdFace() {
        assertEquals("", UNICODE.readOnly());
        assertEquals("Read-only", ASCII.readOnly());
        assertEquals(" > ", ASCII.divider());
        assertEquals(" › ", UNICODE.divider());
    }

    private static StatusBar bar(StatusBar.Glyphs glyphs, List<CapabilityStatus> statuses) {
        return new StatusBar(() -> "Ready", () -> false, new FixedStatusRegistry(
                statuses.stream().map(status -> new CapabilityStatusWidgets(status, List.of())).toList()),
                null, List.of(), glyphs, () -> StatusBar.Navigation.NONE);
    }

    private static List<String> labels(List<StatusBar.Breadcrumb> segments) {
        return segments.stream().map(StatusBar.Breadcrumb::label).toList();
    }

    private static List<String> ids(List<Segment> segments) {
        return segments.stream().map(Segment::id).toList();
    }

    private static NavigationCapability.DocumentSymbol symbol(String name, int kind, int line) {
        return new NavigationCapability.DocumentSymbol(name, kind, line, 0, line, 0, line, 0, line, 0, List.of());
    }
}
