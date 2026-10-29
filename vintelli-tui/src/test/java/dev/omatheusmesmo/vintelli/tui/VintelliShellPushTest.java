package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write ports of {@code vintelli-api} push a highlight and a diff into the
 * shell; both must land visibly in the editor panel of the next frame, so
 * external surfaces such as the MCP server can show their state to the human.
 * The smoke test renders from other threads while the TUI event loop drives
 * the very same shell. The serialisation tests park one call inside a gated
 * tool window and prove a second call cannot enter before the first returns,
 * so a dropped frame lock fails them deterministically.
 */
class VintelliShellPushTest {

    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final int FIRST_EDITOR_ROW = 3;
    private static final int SECOND_EDITOR_ROW = 4;
    private static final int LAST_EDITOR_ROW = 12;
    private static final Duration STARTUP = Duration.ofMillis(100);
    private static final Duration EVENT_WINDOW = Duration.ofMillis(500);

    @Test
    void paintsTheHighlightPushedThroughThePort() {
        VintelliShell shell = defaultShell();
        shell.highlight(new ShellHighlightTarget("src/Main.java", 7));
        Buffer buffer = offlineFrame(shell);

        assertTrue(rowText(buffer, FIRST_EDITOR_ROW).contains("> highlight: src/Main.java:7"),
                rowText(buffer, FIRST_EDITOR_ROW));
    }

    @Test
    void paintsTheDiffPushedThroughThePort() {
        VintelliShell shell = defaultShell();
        shell.show(new ShellUnifiedDiff("example.patch", "@@ -1,3 +1,4 @@\n vintelli state\n+state view"));
        Buffer buffer = offlineFrame(shell);

        assertTrue(rowText(buffer, FIRST_EDITOR_ROW).contains("diff: example.patch"),
                rowText(buffer, FIRST_EDITOR_ROW));
        assertTrue(rowText(buffer, SECOND_EDITOR_ROW).contains("@@ -1,3 +1,4 @@"),
                rowText(buffer, SECOND_EDITOR_ROW));
    }

    @Test
    void clipsLongDiffLinesWithAnExplicitMarker() {
        VintelliShell shell = defaultShell();
        shell.show(new ShellUnifiedDiff("clip.patch", "+" + "x".repeat(260) + "\n y"));
        Buffer buffer = offlineFrame(shell);

        String diffRow = rowText(buffer, SECOND_EDITOR_ROW);
        assertTrue(diffRow.contains("..."), diffRow);
        assertFalse(diffRow.contains("x".repeat(45)), diffRow);
    }

    @Test
    void appendsAMarkerWhenTheDiffDoesNotFitThePanel() {
        VintelliShell shell = defaultShell();
        shell.show(new ShellUnifiedDiff("long.patch", "@@ -1 +1 @@\n" + " ctx\n".repeat(11)));
        Buffer buffer = offlineFrame(shell);

        String markerRow = rowText(buffer, FIRST_EDITOR_ROW + 7);
        assertTrue(markerRow.contains("... (6 more lines)"), markerRow);
    }

    @Test
    void keepsTheEditorPlaceholderWhenNothingWasPushed() {
        VintelliShell shell = defaultShell();
        Buffer buffer = offlineFrame(shell);

        String firstEditorRow = rowText(buffer, FIRST_EDITOR_ROW);
        assertTrue(firstEditorRow.contains("editor tool window"), firstEditorRow);
    }

    @Test
    void rendersFramesWhileTheEventLoopDrivesTheShell() throws Exception {
        VintelliShell shell = defaultShell();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (TuiTestRunner test = TuiTestRunner.runTest(shell, shell::render)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            List<Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                workers.add(pool.submit(() -> {
                    for (int step = 0; step < 200; step++) {
                        shell.highlight(new ShellHighlightTarget("src/concurrent.java", step + 1));
                        shell.show(new ShellUnifiedDiff("concurrent.patch", "@@ -1 +1 @@\n-a\n+b\n ctx"));
                        offlineFrame(shell);
                    }
                }));
            }
            press(pilot, 'j');
            for (Future<?> worker : workers) {
                worker.get(60, TimeUnit.SECONDS);
            }
        }
        pool.shutdownNow();

        Buffer buffer = offlineFrame(shell);
        assertTrue(rowText(buffer, FIRST_EDITOR_ROW).contains("> highlight: src/concurrent.java:200"),
                rowText(buffer, FIRST_EDITOR_ROW));
    }

    @Test
    void keyRoutingWaitsForAFrameThatIsBeingDrawn() throws Exception {
        GatedToolWindow window = new GatedToolWindow();
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState());

        assertSerialised(window.renderGate, () -> offlineFrame(shell),
                () -> shell.handle(KeyEvent.ofChar('x'), null), window.keyGate);
    }

    @Test
    void framesWaitForKeyRoutingThatIsInProgress() throws Exception {
        GatedToolWindow window = new GatedToolWindow();
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState());

        assertSerialised(window.keyGate, () -> shell.handle(KeyEvent.ofChar('x'), null),
                () -> offlineFrame(shell), window.renderGate);
    }

    @Test
    void twoFramesAreNeverDrawnAtTheSameTime() throws Exception {
        GatedToolWindow window = new GatedToolWindow();
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState());

        assertSerialised(window.renderGate, () -> offlineFrame(shell),
                () -> offlineFrame(shell), window.renderGate);
    }

    private static void assertSerialised(Gate firstGate, Runnable first, Runnable second, Gate secondGate)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            firstGate.park();
            Future<?> holder = pool.submit(first);
            assertTrue(firstGate.entered.await(5, TimeUnit.SECONDS), "the first call must reach the tool window");

            Future<?> waiter = pool.submit(second);
            assertThrows(TimeoutException.class, () -> waiter.get(300, TimeUnit.MILLISECONDS),
                    "the second call must wait for the first one to return");
            int entriesOfTheFirstCall = secondGate == firstGate ? 1 : 0;
            assertEquals(entriesOfTheFirstCall, secondGate.entries.get(),
                    "the second call must not reach the tool window while the first one is parked");

            firstGate.release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            waiter.get(5, TimeUnit.SECONDS);
        } finally {
            firstGate.release.countDown();
            pool.shutdownNow();
        }
    }

    private static VintelliShell defaultShell() {
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(), new TerminalToolWindow(), new ProblemsToolWindow(), new AcpAgentsToolWindow()));
        return new VintelliShell(registry.toolWindows(), new ShellState());
    }

    private static Buffer offlineFrame(VintelliShell shell) {
        Buffer buffer = Buffer.empty(VIEWPORT);
        shell.render(Frame.forTesting(buffer));
        return buffer;
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString();
    }

    private static void press(Pilot pilot, char key) {
        pilot.press(key);
        pilot.pause(EVENT_WINDOW);
    }

    /**
     * Counts the calls that reach the tool window and, once parked, holds the
     * first of them until released, so a test controls how long the shell is
     * inside a render or a key routing step.
     */
    private static final class Gate {

        final AtomicInteger entries = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean parked;

        void park() {
            parked = true;
        }

        void pass() {
            entries.incrementAndGet();
            if (!parked) {
                return;
            }
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class GatedToolWindow implements ToolWindow {

        final Gate renderGate = new Gate();
        final Gate keyGate = new Gate();

        @Override
        public String id() {
            return "gated";
        }

        @Override
        public String title() {
            return "Gated";
        }

        @Override
        public char shortcut() {
            return '1';
        }

        @Override
        public int ordinal() {
            return 0;
        }

        @Override
        public void render(Frame frame, Rect area) {
            renderGate.pass();
        }

        @Override
        public boolean handleKey(KeyEvent event) {
            keyGate.pass();
            return true;
        }
    }
}
