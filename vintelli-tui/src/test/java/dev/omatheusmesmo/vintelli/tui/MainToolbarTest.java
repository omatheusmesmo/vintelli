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
 * The main toolbar: the layout at narrow widths, the IntelliJ order of the right
 * group, the ASCII face, the hit areas against the drawn cells and the Run widgets
 * driven through Pilot.
 */
class MainToolbarTest {

    private static final int WIDTH = 120;
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final List<String> MENUS = List.of("File", "Edit", "View");
    private static final MainToolbar.Model CLOSED = new MainToolbar.Model("vintelli", "main", "idea:First", false, false, -1);
    private static final MainToolbar.Glyphs UNICODE = MainToolbar.Glyphs.UNICODE;
    private static final MainToolbar.Glyphs NERD = MainToolbar.Glyphs.of(IconMode.NERD, true);
    private static final MainToolbar.Kind[] RIGHT_GROUP = {
        MainToolbar.Kind.CONFIGURATION, MainToolbar.Kind.RUN, MainToolbar.Kind.DEBUG,
        MainToolbar.Kind.MORE, MainToolbar.Kind.SEARCH, MainToolbar.Kind.SETTINGS };

    @Test
    void narrowWidthDropsTheRightGroupFromTheLeftAndKeepsTheMenuButton() {
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.BRANCH,
                MainToolbar.Kind.CONFIGURATION, MainToolbar.Kind.RUN, MainToolbar.Kind.DEBUG, MainToolbar.Kind.MORE,
                MainToolbar.Kind.SEARCH, MainToolbar.Kind.SETTINGS), kinds(MainToolbar.layout(60, CLOSED, MENUS, UNICODE)));
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.BRANCH,
                MainToolbar.Kind.RUN, MainToolbar.Kind.DEBUG, MainToolbar.Kind.MORE, MainToolbar.Kind.SEARCH,
                MainToolbar.Kind.SETTINGS), kinds(MainToolbar.layout(40, CLOSED, MENUS, UNICODE)));
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.BRANCH,
                MainToolbar.Kind.SEARCH, MainToolbar.Kind.SETTINGS), kinds(MainToolbar.layout(30, CLOSED, MENUS, UNICODE)));
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.SETTINGS),
                kinds(MainToolbar.layout(20, CLOSED, MENUS, UNICODE)));
        List<MainToolbar.Segment> clipped = MainToolbar.layout(14, CLOSED, MENUS, UNICODE);
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT), kinds(clipped));
        assertEquals(" vintelli ▾", clipped.get(1).text());
        assertEquals(List.of(), kinds(MainToolbar.layout(0, CLOSED, MENUS, UNICODE)));
    }

    @Test
    void menuLabelsOpenAtTheColumnsThePopupPanelsUse() {
        MainToolbar.Model open = new MainToolbar.Model("vintelli", "main", "idea:First", false, true, 1);
        List<MainToolbar.Segment> segments = MainToolbar.layout(WIDTH, open, MENUS, UNICODE);
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.MENU, MainToolbar.Kind.MENU,
                MainToolbar.Kind.MENU, MainToolbar.Kind.CONFIGURATION, MainToolbar.Kind.RUN, MainToolbar.Kind.DEBUG,
                MainToolbar.Kind.MORE, MainToolbar.Kind.SEARCH, MainToolbar.Kind.SETTINGS), kinds(segments));
        for (MainToolbar.Segment segment : segments) {
            if (segment.kind() == MainToolbar.Kind.MENU) {
                assertEquals(MenuPopup.labelOffset(MENUS, segment.menuIndex(), MainToolbar.MENU_ORIGIN), segment.x());
            }
        }
    }

    @Test
    void labelAfterAWideMenuLabelOpensWhereItsPopupPanelOpens() {
        List<String> wideMenus = List.of("文件", "Edit", "View");
        MainToolbar.Model open = new MainToolbar.Model("vintelli", "main", "idea:First", false, true, 1);
        List<MainToolbar.Segment> segments = MainToolbar.layout(WIDTH, open, wideMenus, UNICODE);
        MainToolbar.Segment edit = segments.stream()
                .filter(segment -> segment.kind() == MainToolbar.Kind.MENU && segment.menuIndex() == 1)
                .findFirst().orElseThrow();

        assertEquals(MainToolbar.MENU_ORIGIN + MenuPopup.labelWidth("文件"), edit.x());
        assertEquals(MenuPopup.labelOffset(wideMenus, 1, MainToolbar.MENU_ORIGIN), edit.x());
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
        assertTrue(row.contains(" idea:First v "), row);
        assertTrue(row.contains(" > "), row);
        assertTrue(row.trim().endsWith("*"), row);
    }

    @Test
    void hitAreasMatchTheDrawnSegmentsAndRejectOutOfBoundsCells() {
        MainToolbar toolbar = new MainToolbar(() -> CLOSED, MENUS, NERD, MainToolbarTest::themes);
        render(toolbar, WIDTH);
        for (MainToolbar.Segment segment : MainToolbar.layout(WIDTH, CLOSED, MENUS, NERD)) {
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
        MainToolbar toolbar = new MainToolbar(() -> CLOSED, MENUS, NERD, MainToolbarTest::themes);
        render(toolbar, WIDTH);
        assertTrue(toolbar.segmentAt(WIDTH - 2, 0).isPresent(), "the settings button is drawn at the right edge");
        render(toolbar, 20);
        assertTrue(toolbar.segmentAt(WIDTH - 2, 0).isEmpty(), "the old right edge is outside the 20 cell row");
    }

    @Test
    void rightGroupFollowsTheIntelliJOrderWithOneBlankColumnBetweenButtons() {
        List<MainToolbar.Segment> segments = MainToolbar.layout(WIDTH, CLOSED, MENUS, UNICODE);
        List<MainToolbar.Segment> right = segments.subList(3, segments.size());
        assertEquals(List.of(RIGHT_GROUP), kinds(right));
        for (int index = 1; index < right.size(); index++) {
            MainToolbar.Segment previous = right.get(index - 1);
            assertEquals(1, right.get(index).x() - (previous.x() + previous.width()),
                    "one blank column between " + previous.kind() + " and " + right.get(index).kind());
        }
        MainToolbar.Segment settings = right.get(right.size() - 1);
        assertEquals(WIDTH, settings.x() + settings.width(), "the group ends at the right edge of the row");
    }

    @Test
    void twoBlankColumnsSeparateTheGroupsWhenTheRowIsTight() {
        int leftWidth = 3 + 12 + 8;
        int groupWidth = 13 + 2 * 5 + 5;
        List<MainToolbar.Segment> segments = MainToolbar.layout(leftWidth + 2 + groupWidth, CLOSED, MENUS, UNICODE);
        MainToolbar.Segment branch = segments.stream()
                .filter(segment -> segment.kind() == MainToolbar.Kind.BRANCH).findFirst().orElseThrow();
        MainToolbar.Segment configuration = segments.stream()
                .filter(segment -> segment.kind() == MainToolbar.Kind.CONFIGURATION).findFirst().orElseThrow();
        assertEquals(2, configuration.x() - (branch.x() + branch.width()), "two blank columns before the group");
    }

    @Test
    void theRightGroupIsAlwaysAContiguousTailThatEndsWithSettingsAndNeverOverlapsTheLeftGroup() {
        for (int width = 1; width <= 160; width++) {
            List<MainToolbar.Segment> segments = MainToolbar.layout(width, CLOSED, MENUS, UNICODE);
            int previousEnd = 0;
            for (MainToolbar.Segment segment : segments) {
                assertTrue(segment.x() >= previousEnd, "no overlap at " + width + " cells: " + segments);
                previousEnd = segment.x() + segment.width();
            }
            assertTrue(previousEnd <= width, "the row stays inside " + width + " cells: " + segments);
            List<MainToolbar.Kind> kinds = kinds(segments);
            int rightStart = kinds.indexOf(MainToolbar.Kind.CONFIGURATION);
            if (rightStart < 0) {
                rightStart = kinds.indexOf(MainToolbar.Kind.RUN);
            }
            if (rightStart < 0) {
                rightStart = kinds.indexOf(MainToolbar.Kind.DEBUG);
            }
            if (rightStart >= 0) {
                assertEquals(MainToolbar.Kind.SETTINGS, kinds.get(kinds.size() - 1), "settings is the last button at " + width);
                List<MainToolbar.Kind> tail = List.of(RIGHT_GROUP).subList(
                        List.of(RIGHT_GROUP).size() - (kinds.size() - rightStart), List.of(RIGHT_GROUP).size());
                assertEquals(tail, kinds.subList(rightStart, kinds.size()), "the right group drops from the left at " + width);
            }
        }
    }

    @Test
    void theConfigurationDropsFirstAndTheBranchNameStaysAtEightyColumns() {
        MainToolbar.Model real = new MainToolbar.Model("quarkus-langchain4j",
                "test/oracle-native-integration-test", "Current File", false, false, -1);
        List<MainToolbar.Segment> segments = MainToolbar.layout(80, real, MENUS, UNICODE);
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.BRANCH,
                MainToolbar.Kind.RUN, MainToolbar.Kind.DEBUG, MainToolbar.Kind.MORE, MainToolbar.Kind.SEARCH,
                MainToolbar.Kind.SETTINGS), kinds(segments));
        MainToolbar.Segment branch = segments.get(2);
        assertTrue(branch.text().contains("test/oracle"), "the branch name is kept: " + branch.text());
    }

    @Test
    void theSlotDrawsStopWhileAConfigurationRunsAndPlayWhileIdle() {
        MainToolbar.Model running = new MainToolbar.Model("vintelli", "main", "idea:First", true, false, -1);
        List<MainToolbar.Segment> busy = MainToolbar.layout(WIDTH, running, MENUS, UNICODE);
        assertTrue(kinds(busy).contains(MainToolbar.Kind.RUN_STOP));
        assertFalse(kinds(busy).contains(MainToolbar.Kind.RUN));
        assertEquals(UNICODE.stop(), segmentOf(busy, MainToolbar.Kind.RUN_STOP).text().trim());
        List<MainToolbar.Segment> idle = MainToolbar.layout(WIDTH, CLOSED, MENUS, UNICODE);
        assertTrue(kinds(idle).contains(MainToolbar.Kind.RUN));
        assertFalse(kinds(idle).contains(MainToolbar.Kind.RUN_STOP));
        assertEquals(UNICODE.play(), segmentOf(idle, MainToolbar.Kind.RUN).text().trim());
    }

    @Test
    void runButtonDispatchesRunExecuteThroughTheRunToolWindow() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(true);
        try (Fixture fixture = new Fixture(provider, WIDTH)) {
            Pilot pilot = fixture.pilot();
            pilot.click(column(fixture.awaitRow("idea:First"), glyphs().play()), 0);
            assertTrue(provider.started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the run never started");
            assertEquals("idea:First", provider.startedConfiguration.get());
        }
    }

    @Test
    void configurationButtonOpensTheChooserBeforeChangingTheSelection() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(true);
        try (Fixture fixture = new Fixture(provider, WIDTH)) {
            Pilot pilot = fixture.pilot();
            String row = fixture.awaitRow("idea:First");
            pilot.click(row.indexOf("idea:First") + 2, 0);
            pilot.pause(Duration.ofMillis(60));
            pilot.findElement("run-configuration-picker");
            assertTrue(fixture.rendered.get().contains("idea:First"), fixture.rendered.get());
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.ENTER);
            pilot.pause(Duration.ofMillis(100));
            assertEquals("idea:Second", fixture.run.selectedConfigurationLabel(), fixture.screen());
            fixture.awaitRow("idea:Second");
            assertEquals(1L, provider.started.getCount(), "selecting a configuration never starts a run");
        }
    }

    @Test
    void rightGroupReadsInIntelliJOrderAtWideWidth() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(true);
        try (Fixture fixture = new Fixture(provider, 150)) {
            String row = fixture.awaitRow("idea:First");
            int configuration = row.indexOf("idea:First");
            int run = row.indexOf(glyphs().play());
            int debug = row.lastIndexOf(glyphs().debug());
            int more = row.lastIndexOf(glyphs().more());
            int search = row.lastIndexOf(glyphs().search());
            int settings = row.lastIndexOf(glyphs().settings());
            assertTrue(configuration < run && run < debug && debug < more && more < search && search < settings,
                    "the right group must read configuration, run, debug, more, search, settings: " + row);
            assertEquals(3, debug - run, "one blank column between the run and debug buttons");
            assertEquals(3, more - debug, "one blank column between the debug and more buttons");
            assertEquals(3, search - more, "one blank column between the more and search buttons");
            assertEquals(3, settings - search, "one blank column between the search and settings buttons");
            assertEquals(150 - 1, settings, "the settings glyph is the last cell of the 150 cell row");
        }
    }

    @Test
    void runButtonIsInertWhileTheMenuPopupIsOpen() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(true);
        try (Fixture fixture = new Fixture(provider, WIDTH)) {
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

    @Test
    void stopButtonCancelsACancellableRun() throws Exception {
        FakeRunProvider provider = new FakeRunProvider(true);
        try (Fixture fixture = new Fixture(provider, WIDTH)) {
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
        try (Fixture fixture = new Fixture(provider, WIDTH)) {
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
        MainToolbar.Model wide = new MainToolbar.Model("vintelli", "機能/日本語ブランチ", "idea:First", false, false, -1);
        MainToolbar toolbar = new MainToolbar(() -> wide, MENUS, NERD, MainToolbarTest::themes);
        Rect area = new Rect(0, 0, WIDTH, 1);
        Buffer buffer = Buffer.empty(area);
        toolbar.render(Frame.forTesting(buffer), area, DefaultRenderContext.createEmpty());

        List<MainToolbar.Segment> segments = MainToolbar.layout(WIDTH, wide, MENUS, NERD);
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
        assertEquals(List.of(MainToolbar.Kind.MENU_BUTTON, MainToolbar.Kind.PROJECT, MainToolbar.Kind.BRANCH,
                MainToolbar.Kind.MORE, MainToolbar.Kind.SEARCH, MainToolbar.Kind.SETTINGS),
                kinds(narrow), "at 50 cells the configuration and the run slot do not fit beside the wide branch");
        int narrowEnd = 0;
        for (MainToolbar.Segment segment : narrow) {
            assertTrue(segment.x() >= narrowEnd, "no overlap at 50 cells: " + narrow);
            narrowEnd = segment.x() + segment.width();
        }

        int playCell = cellOf(buffer, NERD.play());
        MainToolbar.Segment run = segmentOf(segments, MainToolbar.Kind.RUN);
        assertEquals(run.x() + 1, playCell, "the play glyph is drawn where its hit area starts");
        assertEquals(MainToolbar.Kind.RUN, toolbar.segmentAt(playCell, 0).orElseThrow().kind());
        MainToolbar.Segment settings = segments.get(segments.size() - 1);
        assertEquals(WIDTH, settings.x() + settings.width(), "the settings button ends at the right edge of the row");
    }

    private static MainToolbar.Segment segmentOf(List<MainToolbar.Segment> segments, MainToolbar.Kind kind) {
        return segments.stream().filter(segment -> segment.kind() == kind).findFirst().orElseThrow();
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
        return MainToolbar.Glyphs.of(FileIcons.resolve().mode(), ProjectTreeGlyphs.supportsUnicode());
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
        private final AtomicReference<Buffer> captured = new AtomicReference<>();
        private final RunToolWindow run;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final int width;

        private Fixture(FakeRunProvider provider, int width) throws Exception {
            this.width = width;
            ShellThemes themes = themes();
            UiWorkQueue uiWork = new UiWorkQueue();
            CapabilityRegistry registry = new FakeRegistry(provider);
            run = new RunToolWindow(() -> themes, registry, uiWork, ".");
            List<ToolWindow> windows = List.of(run);
            VintelliShell shell = new VintelliShell(ShellConfig.of(windows, new ShellState(), themes)
                    .withKeymapSupplier(() -> Keymap.defaultKeymap(windows))
                    .withProjectSearch(null)
                    .withNavigationProviders(List.of())
                    .withCapabilityRegistry(registry));
            shell.useUiWorkQueue(uiWork);
            run.refreshConfigurations();
            TestBackend backend = new TestBackend(width, 24);
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
                    .postRenderProcessor((frame, elements, styles, focus, elapsed) -> {
                        rendered.set(rowOf(frame.buffer(), width));
                        captured.set(frame.buffer().copy());
                    })
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

        private String screen() {
            Buffer buffer = captured.get();
            if (buffer == null) return "No frame captured";
            StringBuilder text = new StringBuilder();
            for (int row = 0; row < buffer.height(); row++) {
                for (int column = 0; column < buffer.width(); column++) text.append(buffer.get(column, row).symbol());
                text.append('\n');
            }
            return text.toString();
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
                pilot.resize(width, 24);
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
            return CapabilityResult.ok(List.of(new RunConfiguration("idea:First", "First launch"),
                    new RunConfiguration("idea:Second", "Second launch")));
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
