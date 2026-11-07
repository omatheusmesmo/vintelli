package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.menu.MenuPopup;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.toolkit.app.ToolkitPilot;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;

/**
 * The main toolbar: the layout at narrow widths, the ASCII face, the hit areas
 * against the drawn cells and the Run widget driven through Pilot.
 */
class MainToolbarTest {

    private static final int WIDTH = 120;
    private static final Rect VIEWPORT = new Rect(0, 0, WIDTH, 1);
    private static final List<String> MENUS = List.of("File", "Edit", "View");
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final MainToolbar.Model CLOSED = new MainToolbar.Model("vintelli", "main", "idea:First", false, -1);
    private static final MainToolbar.Glyphs UNICODE = MainToolbar.Glyphs.UNICODE;

    @Test
    void narrowWidthDropsTheRunGroupThenTheBranchAndKeepsTheMenuButton() {
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.BRANCH,
                MainToolbar.Kind.RUN, MainToolbar.Kind.RUN_STOP), kinds(MainToolbar.layout(60, CLOSED, MENUS, UNICODE)));
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.BRANCH),
                kinds(MainToolbar.layout(40, CLOSED, MENUS, UNICODE)));
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT),
                kinds(MainToolbar.layout(20, CLOSED, MENUS, UNICODE)));
        List<MainToolbar.Segment> clipped = MainToolbar.layout(14, CLOSED, MENUS, UNICODE);
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT), kinds(clipped));
        assertEquals(" vintelli ▾", clipped.get(1).text());
        assertEquals(List.of(), kinds(MainToolbar.layout(0, CLOSED, MENUS, UNICODE)));
    }

    @Test
    void menuLabelsOpenAtTheColumnsThePopupPanelsUse() {
        MainToolbar.Model open = new MainToolbar.Model("vintelli", "main", "idea:First", true, 1);
        List<MainToolbar.Segment> segments = MainToolbar.layout(WIDTH, open, MENUS, UNICODE);
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.MENU, MainToolbar.Kind.MENU,
                MainToolbar.Kind.MENU, MainToolbar.Kind.RUN, MainToolbar.Kind.RUN_STOP), kinds(segments));
        for (MainToolbar.Segment segment : segments) {
            if (segment.kind() == MainToolbar.Kind.MENU) {
                assertEquals(MenuPopup.labelOffset(MENUS, segment.menuIndex(), MainToolbar.MENU_ORIGIN), segment.x());
            }
        }
    }

    @Test
    void asciiFaceDrawsNoNonAsciiCell() {
        MainToolbar toolbar = new MainToolbar(() -> CLOSED, MENUS, IconMode.ASCII, MainToolbarTest::themes);
        String row = render(toolbar, WIDTH);
        for (int index = 0; index < row.length(); index++) {
            assertTrue(row.charAt(index) < 128, "non-ASCII cell in the ASCII toolbar: " + row);
        }
        assertTrue(row.startsWith(" =  vintelli v "), row);
        assertTrue(row.contains(" main v "), row);
        assertTrue(row.contains(" > idea:First "), row);
        assertTrue(row.trim().endsWith("#"), row);
    }

    @Test
    void hitAreasMatchTheDrawnSegmentsAndRejectOutOfBoundsCells() {
        MainToolbar toolbar = new MainToolbar(() -> CLOSED, MENUS, IconMode.NERD, MainToolbarTest::themes);
        render(toolbar, WIDTH);
        for (MainToolbar.Segment segment : MainToolbar.layout(WIDTH, CLOSED, MENUS, UNICODE)) {
            assertEquals(segment, toolbar.segmentAt(segment.x(), 0).orElseThrow());
            assertEquals(segment, toolbar.segmentAt(segment.x() + segment.width() - 1, 0).orElseThrow());
        }
        assertTrue(toolbar.segmentAt(-1, 0).isEmpty());
        assertTrue(toolbar.segmentAt(WIDTH, 0).isEmpty());
        assertTrue(toolbar.segmentAt(0, 1).isEmpty());
        assertTrue(toolbar.segmentAt(30, 0).isEmpty(), "the empty middle of the row is not a widget");
    }

    @Test
    void hitAreasFollowTheLatestRenderedWidth() {
        MainToolbar toolbar = new MainToolbar(() -> CLOSED, MENUS, IconMode.NERD, MainToolbarTest::themes);
        render(toolbar, WIDTH);
        assertTrue(toolbar.segmentAt(WIDTH - 2, 0).isPresent(), "the run stop is drawn at the right edge");
        render(toolbar, 20);
        assertTrue(toolbar.segmentAt(WIDTH - 2, 0).isEmpty(), "the old right edge is outside the 20 cell row");
    }

    @Test
    void runButtonDispatchesRunExecuteThroughTheRunToolWindow() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(true);
        try (Fixture fixture = new Fixture(provider)) {
            Pilot pilot = fixture.pilot();
            pilot.click(column(fixture.awaitRow("idea:First"), glyphs().play()), 0);
            assertTrue(provider.started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the run never started");
            assertEquals("idea:First", provider.startedConfiguration.get());
        }
    }

    @Test
    void stopButtonCancelsACancellableRun() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(true);
        try (Fixture fixture = new Fixture(provider)) {
            Pilot pilot = fixture.pilot();
            pilot.click(column(fixture.awaitRow("idea:First"), glyphs().play()), 0);
            assertTrue(provider.started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            pilot.pause(Duration.ofMillis(200));
            pilot.click(column(fixture.awaitRow("idea:First"), glyphs().stop()), 0);
            assertTrue(provider.cancelled.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the stop button never cancelled");
        }
    }

    @Test
    void stopButtonDoesNothingWhenTheRunCannotBeCancelled() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(false);
        try (Fixture fixture = new Fixture(provider)) {
            Pilot pilot = fixture.pilot();
            pilot.click(column(fixture.awaitRow("idea:First"), glyphs().play()), 0);
            assertTrue(provider.started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            pilot.pause(Duration.ofMillis(200));
            pilot.click(column(fixture.awaitRow("idea:First"), glyphs().stop()), 0);
            assertFalse(provider.cancelled.await(300, TimeUnit.MILLISECONDS), "a non cancellable run must not be stopped");
        }
    }

    @Test
    void hitAreasStayOnTheDrawnCellsWhenTheBranchHasWideCharacters() {
        MainToolbar.Model wide = new MainToolbar.Model("vintelli", "機能/日本語ブランチ", "idea:First", false, -1);
        MainToolbar toolbar = new MainToolbar(() -> wide, MENUS, IconMode.NERD, MainToolbarTest::themes);
        Rect area = new Rect(0, 0, WIDTH, 1);
        Buffer buffer = Buffer.empty(area);
        toolbar.render(Frame.forTesting(buffer), area, DefaultRenderContext.createEmpty());

        List<MainToolbar.Segment> segments = MainToolbar.layout(WIDTH, wide, MENUS, UNICODE);
        int previousEnd = 0;
        for (MainToolbar.Segment segment : segments) {
            assertTrue(segment.x() >= previousEnd, "segments must not overlap: " + segments);
            previousEnd = segment.x() + segment.width();
            assertEquals(segment, toolbar.segmentAt(segment.x(), 0).orElseThrow());
            assertEquals(segment, toolbar.segmentAt(segment.x() + segment.width() - 1, 0).orElseThrow());
        }
        assertTrue(previousEnd <= WIDTH);
        MainToolbar.Segment branch = segments.stream()
                .filter(segment -> segment.kind() == MainToolbar.Kind.BRANCH).findFirst().orElseThrow();
        assertEquals(" 機能/日本語ブランチ ▾ ", branch.text(), "the wide branch label is kept whole");
        assertEquals(23, branch.width(), "the branch covers its 23 drawn cells, not its 14 characters");
        List<MainToolbar.Segment> narrow = MainToolbar.layout(50, wide, MENUS, UNICODE);
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.BRANCH),
                kinds(narrow), "at 50 cells the run group does not fit beside the wide branch and is dropped");
        int narrowEnd = 0;
        for (MainToolbar.Segment segment : narrow) {
            assertTrue(segment.x() >= narrowEnd, "no overlap at 50 cells: " + narrow);
            narrowEnd = segment.x() + segment.width();
        }

        int stopCell = cellOf(buffer, UNICODE.stop());
        int playCell = cellOf(buffer, UNICODE.play());
        MainToolbar.Segment stop = segments.stream()
                .filter(segment -> segment.kind() == MainToolbar.Kind.RUN_STOP).findFirst().orElseThrow();
        MainToolbar.Segment run = segments.stream()
                .filter(segment -> segment.kind() == MainToolbar.Kind.RUN).findFirst().orElseThrow();
        assertEquals(stop.x() + 1, stopCell, "the stop glyph is drawn where its hit area starts");
        assertEquals(run.x() + 1, playCell, "the play glyph is drawn where its hit area starts");
        assertEquals(MainToolbar.Kind.RUN_STOP, toolbar.segmentAt(stopCell, 0).orElseThrow().kind());
        assertEquals(MainToolbar.Kind.RUN, toolbar.segmentAt(playCell, 0).orElseThrow().kind());
        assertEquals(WIDTH, stop.x() + stop.width(), "the run group ends at the right edge of the row");
    }

    @Test
    void runButtonIsInertWhileTheMenuPopupIsOpen() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(true);
        try (Fixture fixture = new Fixture(provider)) {
            Pilot pilot = fixture.pilot();
            fixture.awaitRow("idea:First");
            pilot.click(1, 0);
            String row = fixture.awaitRow("File");
            assertTrue(row.contains("idea:First"), "the run widget stays drawn behind the menu: " + row);
            pilot.click(column(row, glyphs().play()), 0);
            assertFalse(provider.started.await(400, TimeUnit.MILLISECONDS),
                    "a click on the run widget behind an open menu must not start a run");
        }
    }

    private static int cellOf(Buffer buffer, String glyph) {
        for (int x = 0; x < buffer.width(); x++) {
            if (glyph.equals(buffer.get(x, 0).symbol())) {
                return x;
            }
        }
        throw new AssertionError("the toolbar does not draw " + glyph);
    }

    private static MainToolbar.Glyphs glyphs() {
        return MainToolbar.Glyphs.of(FileIcons.resolve().mode());
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private static List<MainToolbar.Kind> kinds(List<MainToolbar.Segment> segments) {
        List<MainToolbar.Kind> kinds = new ArrayList<>();
        segments.forEach(segment -> kinds.add(segment.kind()));
        return kinds;
    }

    private static int column(String row, String glyph) {
        int column = row.indexOf(glyph);
        assertTrue(column >= 0, "the toolbar does not draw " + glyph + ": " + row);
        return column;
    }

    private static String render(MainToolbar toolbar, int width) {
        Rect area = new Rect(0, 0, width, 1);
        Buffer buffer = Buffer.empty(area);
        toolbar.render(Frame.forTesting(buffer), area, DefaultRenderContext.createEmpty());
        return rowOf(buffer, width);
    }

    private static String rowOf(Buffer buffer, int width) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < width; x++) {
            text.append(buffer.get(x, 0).symbol());
        }
        return text.toString();
    }

    /**
     * A shell with a standalone Run tool window whose provider is the fake. The
     * shell runs in its own runner, so the first row is captured from the
     * post-render processor on the render thread, the way the shell tests do.
     */
    private static final class Fixture implements AutoCloseable {
        private final ToolkitRunner runner;
        private final Pilot pilot;
        private final Thread renderThread;
        private final AtomicReference<String> rendered = new AtomicReference<>("");
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Fixture(FakeRunProvider provider) throws Exception {
            ShellThemes themes = themes();
            UiWorkQueue uiWork = new UiWorkQueue();
            CapabilityRegistry registry = new FakeRegistry(provider);
            RunToolWindow run = new RunToolWindow(() -> themes, registry, uiWork, ".");
            List<ToolWindow> windows = List.of(run);
            VintelliShell shell = new VintelliShell(windows, new ShellState(), () -> Keymap.defaultKeymap(windows),
                    themes, null, List.of(), registry);
            shell.useUiWorkQueue(uiWork);
            run.refreshConfigurations();
            TestBackend backend = new TestBackend(WIDTH, 24);
            TuiConfig config = TuiConfig.builder()
                    .rawMode(false)
                    .alternateScreen(false)
                    .hideCursor(false)
                    .mouseCapture(true)
                    .shutdownHook(false)
                    .pollTimeout(Duration.ofMillis(10))
                    .noTick()
                    .backend(backend)
                    .build();
            runner = ToolkitRunner.builder()
                    .config(config)
                    .styleEngine(themes.engine())
                    .postRenderProcessor((frame, elements, styles, focus, elapsed) ->
                            rendered.set(rowOf(frame.buffer(), WIDTH)))
                    .build();
            runner.eventRouter().addGlobalHandler(shell);
            shell.bindQuit(runner::quit);
            renderThread = new Thread(() -> {
                try {
                    runner.run(shell::root);
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "main-toolbar-test");
            renderThread.setDaemon(true);
            renderThread.start();
            pilot = new ToolkitPilot(runner, backend);
        }

        private Pilot pilot() {
            return pilot;
        }

        /**
         * Waits for the row to show {@code text}. The runner renders only when
         * an event arrives (no tick), and the provider results are applied by
         * the next render, so each round resizes to the same size to force a frame.
         */
        private String awaitRow(String text) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            String row;
            do {
                pilot.resize(WIDTH, 24);
                pilot.pause(Duration.ofMillis(30));
                row = rendered.get();
            } while (!row.contains(text) && System.nanoTime() < deadline);
            assertTrue(failure.get() == null, "the render thread failed: " + failure.get());
            assertTrue(row.contains(text), "the toolbar never showed " + text + ": " + row);
            return row;
        }

        @Override
        public void close() throws InterruptedException {
            runner.quit();
            renderThread.join(1000);
        }
    }

    private static final class FakeRunProvider implements RunCapability {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicReference<String> startedConfiguration = new AtomicReference<>();
        private final boolean cancellable;

        private FakeRunProvider(boolean cancellable) {
            this.cancellable = cancellable;
        }

        @Override public String identifier() { return "idea"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "IDEA MCP"; }

        @Override
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            return CapabilityResult.ok(List.of(new RunConfiguration("idea:First", "First launch")));
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output) {
            return start(request, output, ended -> { });
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output,
                Consumer<RunCompletion> completion) {
            startedConfiguration.set(request.configurationName());
            started.countDown();
            return CapabilityResult.ok(new RunHandle() {
                @Override
                public void cancel() {
                    cancelled.countDown();
                }

                @Override
                public boolean supportsCancellation() {
                    return cancellable;
                }
            });
        }
    }

    private static final class FakeRegistry implements CapabilityRegistry {
        private final RunCapability provider;

        private FakeRegistry(RunCapability provider) {
            this.provider = provider;
        }

        @Override
        public CapabilitySelection<RunCapability> run() {
            return CapabilitySelection.selected(CapabilityType.RUN, provider);
        }

        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { return unavailable(CapabilityType.DIAGNOSTICS); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }

        @Override
        public List<CapabilityStatus> snapshot() {
            return List.of(CapabilityStatus.available(CapabilityType.RUN, provider.identifier(),
                    provider.provenance(), List.of()));
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }
}
