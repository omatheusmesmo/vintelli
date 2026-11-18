package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayoutStore;
import dev.omatheusmesmo.vintelli.tui.menu.MenuEntry;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.toolkit.app.ToolkitPilot;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusEditorShellTest {

    private static final Size WIDE = new Size(150, 42);
    private static final Size NARROW = new Size(80, 24);
    private static final String PROJECT = "project-panel";
    private static final String EDITOR = "editor-panel";
    private static final String BOTTOM = "bottom-stripe";
    private static final int STRIP_WIDTH = ToolStripElement.WIDTH;
    private static final int TOOLBAR_ROWS = 1;
    private static final int STATUS_ROWS = 1;
    private static final Duration STARTUP = Duration.ofMillis(100);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FRAME_GRACE = Duration.ofMillis(300);
    private static final Duration SETTLE_STEP = Duration.ofMillis(2);

    @TempDir
    Path temporary;

    @Test
    void focusEditorHidesEveryIslandAndTheSecondPressRestoresTheSizesAtWide() throws Exception {
        assertFocusEditorRoundTrip(WIDE);
    }

    @Test
    void focusEditorHidesEveryIslandAndTheSecondPressRestoresTheSizesAtNarrow() throws Exception {
        assertFocusEditorRoundTrip(NARROW);
    }

    @Test
    void openingAWindowFromItsShortcutLeavesFocusModeAndRestoresTheSavedSet() throws Exception {
        VintelliShell shell = shellWithIslands(null, true);
        try (ShellRunner test = new ShellRunner(shell, WIDE)) {
            test.press(KeyCode.F11, KeyModifiers.CTRL);
            assertFalse(shell.isToolWindowVisible("problems"), "focus hides the bottom windows");

            test.press('6', KeyModifiers.ALT);

            assertTrue(shell.isToolWindowVisible("problems"), shell.status());
            assertTrue(shell.isToolWindowVisible("run"), "the windows visible before focus come back");
            assertTrue(shell.isToolWindowVisible("terminal"), "the windows visible before focus come back");
            assertTrue(shell.isToolWindowVisible("acp.agents"), "the right window comes back");
            assertNotNull(test.area(PROJECT), "the project pane comes back with the rest of the set");
            assertTrue(test.area(EDITOR).width() < WIDE.width() - 2 * STRIP_WIDTH,
                    "the editor gives the columns back to the restored panes");
        }
    }

    @Test
    void clickingAStripeIconWhileFocusedOpensItsWindowAndLeavesFocusMode() throws Exception {
        VintelliShell shell = shellWithIslands(null, true);
        try (ShellRunner test = new ShellRunner(shell, WIDE)) {
            test.press(KeyCode.F11, KeyModifiers.CTRL);
            int runIcon = test.rowOf(1, "R");
            assertTrue(runIcon >= 0, "the Run icon stays on the left stripe while focused");

            test.click(1, runIcon);

            assertTrue(shell.isToolWindowVisible("run"), shell.status());
            assertTrue(shell.isToolWindowVisible("terminal"), "the other stored windows come back");
            assertTrue(shell.isToolWindowVisible("acp.agents"), "the right window comes back");
            assertNotNull(test.area(PROJECT), "the project pane comes back with the rest of the set");
        }
    }

    @Test
    void aSessionThatEndsInFocusModeStartsInItAndRestoresTheSavedSizes() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(temporary, temporary.resolve("project"));
        Rect projectBefore;
        Rect bottomBefore;
        VintelliShell first = shellWithIslands(store, true);
        try (ShellRunner test = new ShellRunner(first, WIDE)) {
            widenProjectAndBottom(test);
            projectBefore = test.area(PROJECT);
            bottomBefore = test.area(BOTTOM);
            test.press(KeyCode.F11, KeyModifiers.CTRL);
        }

        VintelliShell restarted = shellWithIslands(store, false);
        try (ShellRunner test = new ShellRunner(restarted, WIDE)) {
            assertFalse(restarted.isToolWindowVisible("run"), "the focused session starts with its windows hidden");
            assertNull(test.area(PROJECT), "the focused session starts without the project pane");
            assertEquals(fullEditor(WIDE), test.area(EDITOR), "the focused session starts with the full editor");

            test.press(KeyCode.F11, KeyModifiers.CTRL);

            assertEquals(projectBefore, test.area(PROJECT), "the project width is restored after the restart");
            assertEquals(bottomBefore, test.area(BOTTOM), "the bottom height is restored after the restart");
            assertTrue(restarted.isToolWindowVisible("run"), "the saved windows come back after the restart");
            assertTrue(restarted.isToolWindowVisible("acp.agents"), "the saved right window comes back");
        }
    }

    @Test
    void theViewMenuOffersFocusEditorWithoutAnUnavailableReason() {
        List<MenuEntry> entries = new ShellMenuCatalog(new NoEditorHost()).entries();
        MenuEntry focus = entries.stream()
                .filter(entry -> entry.actionId().equals(KeyActions.FOCUS_EDITOR))
                .findFirst()
                .orElseThrow();

        assertEquals("View", focus.menu());
        assertEquals("Focus Editor", focus.label());
        assertEquals("", focus.unavailableReason());
    }

    private void assertFocusEditorRoundTrip(Size size) throws Exception {
        VintelliShell shell = shellWithIslands(null, true);
        try (ShellRunner test = new ShellRunner(shell, size)) {
            widenProjectAndBottom(test);
            Rect projectBefore = test.area(PROJECT);
            Rect bottomBefore = test.area(BOTTOM);
            Rect editorBefore = test.area(EDITOR);
            assertNotNull(projectBefore, size + ": the project pane is drawn before focus");
            assertTrue(bottomBefore.height() > 0, size + ": the bottom pane is drawn before focus");

            test.press(KeyCode.F11, KeyModifiers.CTRL);

            assertNull(test.area(PROJECT), size + ": focus hides the project pane");
            assertEquals(0, heightOf(test, BOTTOM), size + ": focus hides the bottom pane");
            assertFalse(shell.isToolWindowVisible("run"), size + ": focus hides the bottom windows");
            assertFalse(shell.isToolWindowVisible("acp.agents"), size + ": focus hides the right windows");
            assertEquals(fullEditor(size), test.area(EDITOR),
                    size + ": the editor takes the whole space between the toolbar and the status row");

            test.press(KeyCode.F11, KeyModifiers.CTRL);

            assertEquals(projectBefore, test.area(PROJECT), size + ": the project width is restored exactly");
            assertEquals(bottomBefore, test.area(BOTTOM), size + ": the bottom height is restored exactly");
            assertEquals(editorBefore, test.area(EDITOR), size + ": the editor returns to its split");
            assertTrue(shell.isToolWindowVisible("run"), size + ": the bottom windows come back");
            assertTrue(shell.isToolWindowVisible("acp.agents"), size + ": the right window comes back");
        }
    }

    private static void widenProjectAndBottom(ShellRunner test) {
        test.press('=', KeyModifiers.ALT);
        test.press('=', KeyModifiers.ALT);
        test.press('.', KeyModifiers.ALT);
        test.press('.', KeyModifiers.ALT);
    }

    private static Rect fullEditor(Size size) {
        return new Rect(STRIP_WIDTH, TOOLBAR_ROWS, size.width() - 2 * STRIP_WIDTH,
                size.height() - TOOLBAR_ROWS - STATUS_ROWS);
    }

    private static int heightOf(ShellRunner test, String id) {
        Rect area = test.area(id);
        return area == null ? 0 : area.height();
    }

    private static VintelliShell shellWithIslands(ShellLayoutStore store, boolean showAcp) {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        List<ToolWindow> windows = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                new ProblemsToolWindow(() -> themes), new AcpAgentsToolWindow(() -> themes))).toolWindows();
        ShellConfig config = ShellConfig.of(windows, new ShellState(), themes);
        if (store != null) {
            config = config.withLayoutStore(store);
        }
        VintelliShell shell = new VintelliShell(config);
        if (showAcp) {
            shell.showToolWindow("acp.agents");
        }
        return shell;
    }

    private static final class NoEditorHost implements ShellMenuCatalog.Host {

        @Override
        public boolean editorAvailable() {
            return true;
        }

        @Override
        public boolean documentOpen() {
            return false;
        }

        @Override
        public boolean dirtyEditorTab() {
            return false;
        }

        @Override
        public int projectFileCount() {
            return 0;
        }

        @Override
        public boolean projectSearchAvailable() {
            return true;
        }

        @Override
        public String navigationUnavailableReason() {
            return "";
        }

        @Override
        public List<ToolWindow> toolWindows() {
            return List.of();
        }

        @Override
        public boolean ideaConnectInProgress() {
            return false;
        }

        @Override
        public String vcsUnavailableReason() {
            return "";
        }

        @Override
        public boolean vcsWriteInProgress() {
            return false;
        }
    }

    /**
     * Runs the shell on its own render thread with a test backend. Element areas are read on that thread
     * between frames, because the element registry is cleared and refilled by every frame.
     */
    private static final class ShellRunner implements AutoCloseable {
        private final ToolkitRunner runner;
        private final Pilot pilot;
        private final Thread renderThread;
        private final AtomicReference<String> rendered = new AtomicReference<>("");
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger frames = new AtomicInteger();

        private ShellRunner(VintelliShell shell, Size size) throws Exception {
            TestBackend backend = new TestBackend(size.width(), size.height());
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
                    .styleEngine(shell.themes().engine())
                    .postRenderProcessor((frame, elements, styles, focus, elapsed) -> {
                        rendered.set(text(frame.buffer()));
                        frames.incrementAndGet();
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
            }, "focus-editor-shell-test");
            renderThread.setDaemon(true);
            renderThread.start();
            pilot = new ToolkitPilot(runner, backend);
            pilot.pause(STARTUP);
            settle();
        }

        private void press(char key, KeyModifiers modifiers) {
            pilot.press(key, modifiers);
            settle();
        }

        private void press(KeyCode key, KeyModifiers modifiers) {
            pilot.press(key, modifiers);
            settle();
        }

        private void click(int x, int y) {
            pilot.click(x, y);
            settle();
        }

        private int rowOf(int column, String symbol) {
            String[] rows = rendered.get().split("\n", -1);
            for (int row = 0; row < rows.length; row++) {
                if (rows[row].startsWith(symbol, column)) {
                    return row;
                }
            }
            return -1;
        }

        /** The area of the element in the latest frame, or null when no frame draws it. */
        private Rect area(String id) {
            settle();
            AtomicReference<Rect> area = new AtomicReference<>();
            runner.runOnRenderThread(() -> area.set(runner.elementRegistry().getArea(id)));
            awaitQueued();
            return area.get();
        }

        /** Waits for the queued input to be handled and for the frame it produces. */
        private void settle() {
            int before = frames.get();
            awaitQueued();
            long deadline = System.nanoTime() + FRAME_GRACE.toNanos();
            while (frames.get() == before && System.nanoTime() < deadline) {
                sleep();
            }
            assertHealthy();
        }

        private void awaitQueued() {
            CountDownLatch handled = new CountDownLatch(1);
            runner.runOnRenderThread(handled::countDown);
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (handled.getCount() > 0 && runner.isRunning() && System.nanoTime() < deadline) {
                sleep();
            }
            assertHealthy();
            if (handled.getCount() > 0 && runner.isRunning()) {
                throw new AssertionError("the shell did not handle the queued input within "
                        + SETTLE_TIMEOUT.toMillis() + " ms");
            }
        }

        private void assertHealthy() {
            Throwable problem = failure.get();
            if (problem != null) {
                throw new AssertionError("TUI render thread failed", problem);
            }
        }

        private static void sleep() {
            try {
                TimeUnit.MILLISECONDS.sleep(SETTLE_STEP.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the shell", interrupted);
            }
        }

        @Override
        public void close() throws Exception {
            runner.quit();
            renderThread.join(SETTLE_TIMEOUT.toMillis());
        }
    }

    private static String text(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < buffer.height(); row++) {
            for (int column = 0; column < buffer.width(); column++) {
                text.append(buffer.get(column, row).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
