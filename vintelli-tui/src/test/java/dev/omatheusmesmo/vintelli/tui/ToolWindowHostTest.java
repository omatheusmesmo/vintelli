package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolWindowHostTest {

    private static final ToolWindow RUN = window("run", ToolWindow.Anchor.BOTTOM);
    private static final ToolWindow TERMINAL = window("terminal", ToolWindow.Anchor.BOTTOM);
    private static final ToolWindow PROBLEMS = window("problems", ToolWindow.Anchor.BOTTOM);
    private static final ToolWindow STRUCTURE = window("structure", ToolWindow.Anchor.RIGHT);
    private static final ToolWindow ACP = window("acp.agents", ToolWindow.Anchor.RIGHT);
    private static final ToolWindow FILES = window("files", ToolWindow.Anchor.LEFT);

    private final RecordingHost recorder = new RecordingHost();

    @Test
    void activeWindowIsTheFirstVisibleWindowOfEachAnchor() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL, STRUCTURE, ACP, FILES), Set.of());

        assertEquals(RUN, host.activeWindow(ToolWindow.Anchor.BOTTOM));
        assertEquals(STRUCTURE, host.activeWindow(ToolWindow.Anchor.RIGHT));
        assertEquals(FILES, host.activeWindow(ToolWindow.Anchor.LEFT));
    }

    @Test
    void rightAnchorStartsOnStructureWhenItIsVisible() {
        ToolWindowHost host = host(List.of(ACP, STRUCTURE), Set.of());

        assertEquals(STRUCTURE, host.activeWindow(ToolWindow.Anchor.RIGHT));
    }

    @Test
    void rightAnchorStartsOnItsFirstWindowWhenStructureIsHidden() {
        ToolWindowHost host = host(List.of(ACP, STRUCTURE), Set.of("structure"));

        assertEquals(ACP, host.activeWindow(ToolWindow.Anchor.RIGHT));
        assertFalse(host.visibleWindows(ToolWindow.Anchor.RIGHT).contains(STRUCTURE));
    }

    @Test
    void hiddenWindowsLeaveTheVisibleSetAndTheActiveWindowMovesToTheNextOne() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of("run"));

        assertEquals(List.of(TERMINAL), host.visibleWindows(ToolWindow.Anchor.BOTTOM));
        assertEquals(TERMINAL, host.activeWindow(ToolWindow.Anchor.BOTTOM));
    }

    @Test
    void switchToShowsAHiddenWindowAndMakesItActiveAndFocused() {
        ToolWindowHost host = host(List.of(RUN, PROBLEMS), Set.of("problems"));

        host.switchTo(PROBLEMS);

        assertTrue(host.isVisible("problems"));
        assertEquals(PROBLEMS, host.activeWindow(ToolWindow.Anchor.BOTTOM));
        assertTrue(host.isActive(PROBLEMS));
        assertEquals(List.of("tool-problems"), recorder.focuses);
        assertEquals(List.of("Tool window shown: problems"), recorder.statuses);
        assertEquals(1, recorder.persisted);
    }

    @Test
    void isActiveNeedsTheActiveIndexAndTheFocusTogether() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of());

        assertFalse(host.isActive(RUN));

        host.focusWindow(RUN);
        assertTrue(host.isActive(RUN));
        assertFalse(host.isActive(TERMINAL));

        host.focusWindow(TERMINAL);
        assertFalse(host.isActive(TERMINAL));

        host.clearFocus();
        assertFalse(host.isActive(RUN));
        assertFalse(host.isFocused("run"));
    }

    @Test
    void switchingOneAnchorLeavesTheActiveWindowOfTheOthersAlone() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL, STRUCTURE, ACP), Set.of());

        host.switchTo(TERMINAL);

        assertEquals(TERMINAL, host.activeWindow(ToolWindow.Anchor.BOTTOM));
        assertEquals(1, host.activeIndex(ToolWindow.Anchor.BOTTOM));
        assertEquals(STRUCTURE, host.activeWindow(ToolWindow.Anchor.RIGHT));
    }

    @Test
    void resetReturnsEveryAnchorToItsFirstWindow() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL, STRUCTURE, ACP), Set.of());
        host.switchTo(TERMINAL);
        host.switchTo(ACP);

        host.reset();

        assertEquals(RUN, host.activeWindow(ToolWindow.Anchor.BOTTOM));
        assertEquals(0, host.activeIndex(ToolWindow.Anchor.RIGHT));
        assertEquals(STRUCTURE, host.activeWindow(ToolWindow.Anchor.RIGHT));
    }

    @Test
    void togglingTheActiveWindowHidesItAndReturnsTheNextVisibleWindowToSwitchTo() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of());
        host.focusWindow(RUN);

        Optional<ToolWindow> next = host.toggle("run");

        assertEquals(Optional.of(TERMINAL), next);
        assertFalse(host.isVisible("run"));
        assertEquals(List.of(RUN), recorder.hidden);
        assertTrue(recorder.statuses.isEmpty(), "the shell sets the status when it switches");
        assertEquals(List.of("tool-run", "editor-panel"), recorder.focuses);
        assertFalse(host.isFocused("run"));
    }

    @Test
    void showingAHiddenWindowKeepsTheActiveWindowAndHidingItSwitchesToTheFirstVisibleOne() {
        ToolWindowHost host = host(List.of(RUN, PROBLEMS), Set.of("problems"));

        assertEquals(Optional.empty(), host.toggle("problems"));
        assertTrue(host.isVisible("problems"));
        assertEquals(RUN, host.activeWindow(ToolWindow.Anchor.BOTTOM));

        assertEquals(Optional.of(RUN), host.toggle("problems"));
        assertFalse(host.isVisible("problems"));
        assertEquals(List.of("Tool window shown: problems"), recorder.statuses);
        assertTrue(recorder.hidden.contains(PROBLEMS));
    }

    @Test
    void hidingTheLastVisibleWindowOfAnAnchorLeavesNoSwitchTarget() {
        ToolWindowHost host = host(List.of(RUN), Set.of());

        assertEquals(Optional.empty(), host.toggle("run"));

        assertTrue(host.bottomWindowsHidden());
        assertNull(host.activeWindow(ToolWindow.Anchor.BOTTOM));
        assertEquals(List.of("Tool window hidden: run"), recorder.statuses);
    }

    @Test
    void hidingAWindowOfAnotherAnchorKeepsTheFocusOnTheActiveWindow() {
        ToolWindowHost host = host(List.of(RUN, STRUCTURE, ACP), Set.of());
        host.focusWindow(STRUCTURE);

        host.toggle("acp.agents");

        assertTrue(host.isFocused("structure"));
        assertEquals(List.of("tool-structure"), recorder.focuses);
    }

    @Test
    void hideAndShowSetTheVisibilityAndTheStatusLine() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of());

        host.hide("terminal");
        assertFalse(host.isVisible("terminal"));
        assertEquals(List.of(RUN), host.visibleWindows(ToolWindow.Anchor.BOTTOM));

        host.show("terminal");
        assertTrue(host.isVisible("terminal"));
        assertEquals(List.of("Tool window hidden: terminal", "Tool window shown: terminal"), recorder.statuses);
        assertEquals(2, recorder.persisted);
    }

    @Test
    void defaultBottomPaneCountsTheStatusRowAsOneRowLess() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of());

        assertEquals(ToolWindowHost.BOTTOM_HEIGHT - 1, host.bottomPaneRows(false, 40));
        assertEquals(11, host.bottomPaneRows(false, 40));
    }

    @Test
    void customizedBottomPaneTakesTheStoredHeightLessTheStatusRow() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of());

        host.storeBottomHeight(20, 40);

        assertEquals(19, host.bottomRows(40));
        assertEquals(19, host.bottomPaneRows(true, 40));
    }

    @Test
    void resizingTheBottomPaneMovesTheStoredHeightByTheDelta() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of());
        host.storeBottomHeight(20, 40);

        host.resizeBottom(2, 40);

        assertEquals(21, host.bottomRows(40));
    }

    @Test
    void adoptingTheDefaultBottomHeightKeepsTheDefaultRows() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of());

        host.adoptBottomDefault(40);

        assertEquals(11, host.bottomRows(40));
    }

    @Test
    void bottomPaneHasNoRowsWhileItsWindowsAreHidden() {
        ToolWindowHost host = host(List.of(RUN, TERMINAL), Set.of("run", "terminal"));

        assertTrue(host.bottomWindowsHidden());
        assertEquals(0, host.bottomPaneRows(false, 40));
        assertEquals(0, host.bottomPaneRows(true, 40));
    }

    @Test
    void anAnchorWithoutWindowsUsesTheEmptyBottomHeight() {
        ToolWindowHost host = host(List.of(FILES, STRUCTURE), Set.of());

        assertFalse(host.hasWindows(ToolWindow.Anchor.BOTTOM));
        assertFalse(host.bottomWindowsHidden());
        assertEquals(11, host.defaultBottomHeight());
    }

    private ToolWindowHost host(List<ToolWindow> windows, Set<String> hiddenIds) {
        Set<String> all = windows.stream().map(ToolWindow::id).collect(Collectors.toSet());
        Set<String> visible = new HashSet<>(all);
        visible.removeAll(hiddenIds);
        ShellLayout layout = new ShellLayout(ShellLayout.DEFAULT_PROJECT_RATIO, ShellLayout.DEFAULT_BOTTOM_RATIO,
                visible, all);
        return new ToolWindowHost(windows, layout, recorder);
    }

    private static ToolWindow window(String id, ToolWindow.Anchor anchor) {
        return new FakeToolWindow(id, id, id.charAt(0), 0, id, false, anchor);
    }

    private static final class RecordingHost implements ToolWindowHost.Host {

        private final List<String> focuses = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private final List<ToolWindow> hidden = new ArrayList<>();
        private int persisted;

        @Override
        public void persistLayout() {
            persisted++;
        }

        @Override
        public void moveFocus(String focusId) {
            focuses.add(focusId);
        }

        @Override
        public void windowHidden(ToolWindow window) {
            hidden.add(window);
        }

        @Override
        public void status(String message) {
            statuses.add(message);
        }
    }
}
