package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.PaneLayoutController.Area;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayoutStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaneLayoutControllerTest {

    private static final int VIEWPORT_WIDTH = 120;
    private static final int VIEWPORT_HEIGHT = 40;
    private static final int SPLITTER_X = PaneLayoutController.TOOL_STRIP_WIDTH + PaneLayoutController.PROJECT_PANEL_WIDTH;
    private static final int BOTTOM_SPLITTER_Y = 30;

    @TempDir
    Path tempDir;

    private ShellLayoutStore store;
    private ShellLayout layout;
    private FakeHost host;
    private PaneLayoutController controller;

    @BeforeEach
    void setUp() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        store = new ShellLayoutStore(tempDir.resolve("config"), project);
        layout = store.load();
        host = new FakeHost(layout);
        controller = new PaneLayoutController(layout, store, host);
    }

    @Test
    void theFirstResizeAdoptsTheFixedProjectDefaultBeforeMovingIt() {
        assertFalse(controller.isSizeCustomized());

        controller.resizeProject(2);

        assertTrue(controller.isSizeCustomized());
        int expected = PaneLayoutController.PROJECT_PANEL_WIDTH + 2;
        assertEquals(expected, controller.sizedProjectWidth());
        assertEquals(controller.leftRegionWidth(expected), host.placedProject);
        assertEquals("Project panel: " + expected + " columns", host.lastStatus());
    }

    @Test
    void widerStopsAtTheCapThatKeepsTheEditorItsMinimum() {
        controller.resizeProject(1000);

        int projectBase = VIEWPORT_WIDTH - PaneLayoutController.TOOL_STRIP_WIDTH;
        int cap = projectBase - ShellLayout.MIN_EDITOR_WIDTH - PaneLayoutController.PANE_SEPARATOR_WIDTH;
        assertEquals(cap, controller.sizedProjectWidth());
    }

    @Test
    void anOpenRightWindowLowersTheProjectCap() {
        host.rightRegistered = true;
        host.rightVisible = true;
        host.activeRightIsAgents = true;

        controller.resizeProject(1000);

        int projectBase = VIEWPORT_WIDTH - PaneLayoutController.TOOL_STRIP_WIDTH;
        int rightStripe = PaneLayoutController.ACP_SIDE_PANEL_WIDTH + PaneLayoutController.TOOL_STRIP_WIDTH;
        assertEquals(projectBase - rightStripe - ShellLayout.MIN_EDITOR_WIDTH - PaneLayoutController.PANE_SEPARATOR_WIDTH,
                controller.sizedProjectWidth());
    }

    @Test
    void narrowerStopsAtTheMinimumProjectWidth() {
        controller.resizeProject(-1000);

        assertEquals(ShellLayout.MIN_PROJECT_WIDTH, controller.sizedProjectWidth());
    }

    @Test
    void tallerAndShorterStopAtTheEditorAndBottomMinimums() {
        controller.resizeBottom(1000);
        int available = VIEWPORT_HEIGHT - 1;
        assertEquals(available - ShellLayout.MIN_EDITOR_HEIGHT - 1, host.placedBottom);

        controller.resizeBottom(-1000);
        assertEquals(ShellLayout.MIN_BOTTOM_HEIGHT - 1, host.placedBottom);
    }

    @Test
    void aHiddenOrUnlaidOutShellStartsNoResize() {
        host.layoutReady = false;
        controller.resizeProject(2);
        assertEquals("Project layout is not ready", host.lastStatus());
        assertFalse(controller.isSizeCustomized());

        host.layoutReady = true;
        layout.setProjectVisible(false);
        controller.resizeProject(2);
        assertEquals("Project panel is hidden", host.lastStatus());
        assertEquals(-1, host.placedProject);
    }

    @Test
    void pressingTheProjectSplitterDragsAndTheReleasePersistsTheRatio() throws IOException {
        assertTrue(controller.press(SPLITTER_X, 10, true));
        assertTrue(controller.dragging());
        assertFalse(Files.exists(store.file()), "nothing is written while the drag is in progress");

        assertTrue(controller.drag(40, 10));
        int width = 40 - PaneLayoutController.TOOL_STRIP_WIDTH;
        assertEquals(controller.leftRegionWidth(width), host.placedProject);
        assertEquals("Project panel: " + width + " columns", host.lastStatus());

        assertTrue(controller.release());
        assertFalse(controller.dragging());
        assertEquals((double) width / (VIEWPORT_WIDTH - PaneLayoutController.TOOL_STRIP_WIDTH),
                store.load().projectRatio(), 1e-9);
        assertFalse(controller.release(), "a second release has no drag left to end");
    }

    @Test
    void aPressWithoutMovingWritesNothing() {
        assertTrue(controller.press(SPLITTER_X, 10, true));
        assertTrue(controller.release());

        assertFalse(Files.exists(store.file()));
    }

    @Test
    void cancelEndsTheDragAndKeepsTheSizeItAlreadyApplied() throws IOException {
        controller.press(SPLITTER_X, 10, true);
        controller.drag(40, 10);

        controller.cancel();

        assertFalse(controller.dragging());
        assertEquals(40 - PaneLayoutController.TOOL_STRIP_WIDTH,
                Math.round(store.load().projectRatio() * (VIEWPORT_WIDTH - PaneLayoutController.TOOL_STRIP_WIDTH)));
        assertFalse(controller.drag(50, 10));
    }

    @Test
    void pressesOffTheSplittersOverAnOverlayOrWithTheWrongButtonStartNoDrag() {
        assertFalse(controller.press(10, 10, true));
        assertFalse(controller.press(SPLITTER_X, 10, false));
        host.overlayOpen = true;
        assertFalse(controller.press(SPLITTER_X, 10, true));

        assertFalse(controller.dragging());
    }

    @Test
    void theBottomSplitterSetsTheRowsOfTheBottomPaneAndPersistsOnRelease() throws IOException {
        assertTrue(controller.press(60, BOTTOM_SPLITTER_Y, true));

        assertTrue(controller.drag(60, 25));
        int available = VIEWPORT_HEIGHT - 1;
        int editorHeight = 25 - 1 + 1;
        assertEquals(available - editorHeight - 1, host.placedBottom);

        assertTrue(controller.release());
        assertEquals((double) (available - editorHeight) / available, store.load().bottomRatio(), 1e-9);
    }

    /** A shell stand-in that reports the layout the controller last placed, as the next frame would measure it. */
    private static final class FakeHost implements PaneLayoutController.Host {

        private final ShellLayout layout;
        private final List<String> statuses = new ArrayList<>();
        private Area viewport = new Area(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        private boolean layoutReady = true;
        private boolean bottomHidden;
        private boolean rightRegistered;
        private boolean rightVisible;
        private boolean activeRightIsAgents;
        private boolean overlayOpen;
        private int placedProject = -1;
        private int placedBottom = -1;

        FakeHost(ShellLayout layout) {
            this.layout = layout;
        }

        String lastStatus() {
            return statuses.isEmpty() ? null : statuses.getLast();
        }

        @Override
        public Area viewport() {
            return viewport;
        }

        @Override
        public Area verticalSeparator() {
            return new Area(SPLITTER_X, 1, 1, VIEWPORT_HEIGHT - 2);
        }

        @Override
        public Area horizontalSeparator() {
            return new Area(PaneLayoutController.TOOL_STRIP_WIDTH + 1, BOTTOM_SPLITTER_Y, VIEWPORT_WIDTH - SPLITTER_X - 1, 1);
        }

        @Override
        public int projectPanelWidth() {
            return PaneLayoutController.PROJECT_PANEL_WIDTH;
        }

        @Override
        public int centerWidth() {
            return VIEWPORT_WIDTH - SPLITTER_X - 1;
        }

        @Override
        public int centerHeight() {
            return BOTTOM_SPLITTER_Y;
        }

        @Override
        public boolean layoutReady() {
            return layoutReady;
        }

        @Override
        public boolean bottomWindowsHidden() {
            return bottomHidden;
        }

        @Override
        public int defaultBottomHeight() {
            return ToolWindowHost.BOTTOM_HEIGHT;
        }

        @Override
        public int bottomRows(int availableHeight) {
            return Math.max(0, layout.bottomHeight(availableHeight) - 1);
        }

        @Override
        public boolean rightRegistered() {
            return rightRegistered;
        }

        @Override
        public boolean rightVisible() {
            return rightVisible;
        }

        @Override
        public boolean activeRightIsAgents() {
            return activeRightIsAgents;
        }

        @Override
        public boolean overlayOpen() {
            return overlayOpen;
        }

        @Override
        public void placeProject(int leftRegionWidth) {
            placedProject = leftRegionWidth;
        }

        @Override
        public void placeBottom(int rows) {
            placedBottom = rows;
        }

        @Override
        public void status(String message) {
            statuses.add(message);
        }
    }
}
