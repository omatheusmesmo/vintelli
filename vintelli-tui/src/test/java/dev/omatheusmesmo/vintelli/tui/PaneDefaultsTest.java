package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaneDefaultsTest {

    private static final int CLOSED_RIGHT_STRIPE = PaneLayoutController.TOOL_STRIP_WIDTH;
    private static final int OPEN_ACP_STRIPE = PaneLayoutController.ACP_SIDE_PANEL_WIDTH + PaneLayoutController.TOOL_STRIP_WIDTH;
    private static final int DEFAULT_BOTTOM_ROWS = ToolWindowHost.BOTTOM_HEIGHT - 1;

    @Test
    void theProjectKeepsItsFixedWidthAtEightyByTwentyFourAndWider() {
        assertEquals(PaneLayoutController.PROJECT_PANEL_WIDTH, PaneLayoutController.defaultProjectWidth(80, CLOSED_RIGHT_STRIPE));
        assertEquals(PaneLayoutController.PROJECT_PANEL_WIDTH, PaneLayoutController.defaultProjectWidth(150, CLOSED_RIGHT_STRIPE));
        assertEquals(PaneLayoutController.PROJECT_PANEL_WIDTH, PaneLayoutController.defaultProjectWidth(0, 0));
    }

    @Test
    void theProjectShrinksSoTheEditorKeepsItsFortyColumnsAtSixtyByEighteen() {
        int project = PaneLayoutController.defaultProjectWidth(60, CLOSED_RIGHT_STRIPE);

        assertEquals(13, project);
        assertEquals(PaneLayoutController.DEFAULT_EDITOR_WIDTH, editorColumns(60, project, CLOSED_RIGHT_STRIPE));
    }

    @Test
    void theProjectStopsAtItsMinimumWhenTheEditorCannotKeepFortyColumns() {
        assertEquals(ShellLayout.MIN_PROJECT_WIDTH, PaneLayoutController.defaultProjectWidth(50, CLOSED_RIGHT_STRIPE));
        assertEquals(ShellLayout.MIN_PROJECT_WIDTH, PaneLayoutController.defaultProjectWidth(40, CLOSED_RIGHT_STRIPE));
    }

    @Test
    void theProjectHidesWhenItsMinimumWouldLeaveTheEditorBelowItsMinimumWidth() {
        assertTrue(PaneLayoutController.defaultProjectFits(36, CLOSED_RIGHT_STRIPE));
        assertFalse(PaneLayoutController.defaultProjectFits(35, CLOSED_RIGHT_STRIPE));
        assertTrue(PaneLayoutController.defaultProjectFits(68, OPEN_ACP_STRIPE));
        assertFalse(PaneLayoutController.defaultProjectFits(60, OPEN_ACP_STRIPE));
    }

    @Test
    void aDefaultProjectThatFitsAlwaysLeavesTheEditorItsMinimumWidth() {
        for (int stripe : List.of(CLOSED_RIGHT_STRIPE, OPEN_ACP_STRIPE)) {
            for (int width = 40; width <= 200; width++) {
                if (!PaneLayoutController.defaultProjectFits(width, stripe)) {
                    continue;
                }
                int project = PaneLayoutController.defaultProjectWidth(width, stripe);
                assertTrue(project >= ShellLayout.MIN_PROJECT_WIDTH && project <= PaneLayoutController.PROJECT_PANEL_WIDTH,
                        "project " + project + " at " + width);
                assertTrue(editorColumns(width, project, stripe) >= ShellLayout.MIN_EDITOR_WIDTH,
                        "editor at " + width + " with stripe " + stripe);
            }
        }
    }

    @Test
    void theBottomPaneKeepsItsDefaultRowsAtEightyByTwentyFour() {
        assertEquals(DEFAULT_BOTTOM_ROWS, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 24, false));
    }

    @Test
    void theBottomPaneShrinksSoTheEditorKeepsItsFiveRowsAtSixtyByEighteenAndFiftyByFifteen() {
        assertEquals(10, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 18, false));
        assertEquals(7, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 15, false));
        assertEquals(ShellLayout.MIN_EDITOR_HEIGHT, editorRows(18, 10));
        assertEquals(ShellLayout.MIN_EDITOR_HEIGHT, editorRows(15, 7));
    }

    @Test
    void theBottomPaneHidesWhenItWouldDropBelowItsMinimumRows() {
        assertEquals(0, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 10, false));
        assertEquals(ShellLayout.MIN_BOTTOM_HEIGHT, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 11, false));
    }

    @Test
    void anExplicitlyShownBottomPaneTakesRowsFromTheEditorDownToItsLastTwo() {
        assertEquals(5, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 10, true));
        assertEquals(7, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 12, true));
        assertEquals(ToolWindowHost.EDITOR_ROWS_BESIDE_A_SHOWN_WINDOW, editorRows(12, 7));
        assertEquals(DEFAULT_BOTTOM_ROWS, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 16, true));
        assertEquals(DEFAULT_BOTTOM_ROWS, ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, 24, true));
    }

    @Test
    void anExplicitlyShownBottomPaneNeverDropsBelowItsMinimumRows() {
        for (int height = 1; height <= 60; height++) {
            assertTrue(ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, height, true)
                    >= ShellLayout.MIN_BOTTOM_HEIGHT, "bottom at height " + height);
        }
    }

    @Test
    void aDrawnDefaultBottomPaneNeverTakesTheEditorBelowItsMinimumRows() {
        for (int height = 11; height <= 60; height++) {
            int rows = ToolWindowHost.defaultBottomRows(DEFAULT_BOTTOM_ROWS, height, false);
            if (rows > 0) {
                assertTrue(editorRows(height, rows) >= ShellLayout.MIN_EDITOR_HEIGHT, "editor at height " + height);
                assertTrue(rows >= ShellLayout.MIN_BOTTOM_HEIGHT, "bottom at height " + height);
            }
        }
    }

    @Test
    void aBottomWindowShownExplicitlyRevealsAHiddenDefaultPane() {
        ToolWindow run = new FakeToolWindow("run", "Run", 'R', 0, "run", false, ToolWindow.Anchor.BOTTOM);
        ToolWindowHost host = new ToolWindowHost(List.of(run), new ShellLayout(ShellLayout.DEFAULT_PROJECT_RATIO,
                ShellLayout.DEFAULT_BOTTOM_RATIO, Set.of("run"), Set.of("run")), noopHost());
        assertEquals(0, host.bottomPaneRows(false, 9), "the default pane hides at ten rows");
        assertFalse(host.isBottomRevealed());

        host.show("run");

        assertTrue(host.isBottomRevealed());
        assertEquals(5, host.bottomPaneRows(false, 9));
    }

    @Test
    void switchingToAVisibleBottomWindowGivesItRowsFromTheEditor() {
        ToolWindow run = new FakeToolWindow("run", "Run", 'R', 0, "run", false, ToolWindow.Anchor.BOTTOM);
        ToolWindowHost host = new ToolWindowHost(List.of(run), new ShellLayout(ShellLayout.DEFAULT_PROJECT_RATIO,
                ShellLayout.DEFAULT_BOTTOM_RATIO, Set.of("run"), Set.of("run")), noopHost());
        assertEquals(4, host.bottomPaneRows(false, 11), "the default pane leaves the editor five rows");

        host.switchTo(run);

        assertTrue(host.isBottomRevealed());
        assertEquals(7, host.bottomPaneRows(false, 11));
    }

    @Test
    void hidingTheLastBottomWindowClearsTheExplicitReveal() {
        ToolWindow run = new FakeToolWindow("run", "Run", 'R', 0, "run", false, ToolWindow.Anchor.BOTTOM);
        ToolWindowHost host = new ToolWindowHost(List.of(run), new ShellLayout(ShellLayout.DEFAULT_PROJECT_RATIO,
                ShellLayout.DEFAULT_BOTTOM_RATIO, Set.of("run"), Set.of("run")), noopHost());
        host.show("run");
        assertTrue(host.isBottomRevealed());

        host.hide("run");

        assertFalse(host.isBottomRevealed());
    }

    private static int editorColumns(int viewportWidth, int project, int rightStripe) {
        return viewportWidth - PaneLayoutController.TOOL_STRIP_WIDTH - (project + PaneLayoutController.PANE_SEPARATOR_WIDTH)
                - rightStripe;
    }

    private static int editorRows(int viewportHeight, int bottomRows) {
        return viewportHeight - 2 - bottomRows - 1;
    }

    private static ToolWindowHost.Host noopHost() {
        return new ToolWindowHost.Host() {
            @Override
            public void persistLayout() {
            }

            @Override
            public void moveFocus(String focusId) {
            }

            @Override
            public void windowHidden(ToolWindow window) {
            }

            @Override
            public void status(String message) {
            }
        };
    }
}
