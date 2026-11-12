package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CommandRef;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickFixMenuTest {

    private static final CodeAction FIRST = new CodeAction("First fix", "quickfix", true,
            new WorkspaceEdit(List.of(), List.of()), null);
    private static final CodeAction SECOND = new CodeAction("Second fix", "quickfix", false,
            new WorkspaceEdit(List.of(), List.of()), null);
    private static final CodeAction COMMAND_ONLY = new CodeAction("Suppress", "quickfix", false, null,
            new CommandRef("suppress", "Suppress"));

    @Test
    void theListStartsWithTheFirstActionAndDownAndUpMoveTheSelection() {
        QuickFixMenu menu = menuShowing(FIRST, SECOND, COMMAND_ONLY);

        assertEquals(QuickFixMenu.State.ACTIONS, menu.state());
        assertEquals(0, menu.selectedIndex());
        assertEquals(Optional.empty(), menu.press(OverlayKey.DOWN));
        assertEquals(1, menu.selectedIndex());
        menu.press(OverlayKey.UP);
        assertEquals(0, menu.selectedIndex());
    }

    @Test
    void theSelectionWrapsAroundAtBothEnds() {
        QuickFixMenu menu = menuShowing(FIRST, SECOND, COMMAND_ONLY);

        menu.press(OverlayKey.UP);
        assertEquals(2, menu.selectedIndex(), "Up from the first action wraps to the last");
        menu.press(OverlayKey.DOWN);
        assertEquals(0, menu.selectedIndex(), "Down from the last action wraps to the first");
    }

    @Test
    void enterReturnsTheSelectedActionAndTheMenuStaysOpenUntilTheShellCloses() {
        QuickFixMenu menu = menuShowing(FIRST, SECOND);
        menu.press(OverlayKey.DOWN);

        assertEquals(Optional.of(SECOND), menu.press(OverlayKey.ENTER));
        assertTrue(menu.isOpen());
    }

    @Test
    void anEmptyListShowsTheEmptyStateAndEnterSelectsNothing() {
        QuickFixMenu menu = new QuickFixMenu();
        long ticket = menu.begin();

        menu.showActions(ticket, List.of());

        assertEquals(QuickFixMenu.State.EMPTY, menu.state());
        assertTrue(menu.actions().isEmpty());
        assertEquals(Optional.empty(), menu.selected());
        assertEquals(Optional.empty(), menu.press(OverlayKey.ENTER));
        assertEquals(QuickFixMenu.State.EMPTY, menu.state());
    }

    @Test
    void anUnavailableProviderKeepsItsReasonAndOffersNoAction() {
        QuickFixMenu menu = new QuickFixMenu();
        long ticket = menu.begin();

        menu.showUnavailable(ticket, "no language server profile is configured");

        assertEquals(QuickFixMenu.State.UNAVAILABLE, menu.state());
        assertEquals("no language server profile is configured", menu.message());
        assertEquals(Optional.empty(), menu.press(OverlayKey.ENTER));
    }

    @Test
    void aFailedRequestKeepsItsReasonAndOffersNoAction() {
        QuickFixMenu menu = new QuickFixMenu();
        long ticket = menu.begin();

        menu.showFailure(ticket, "malformed: bad payload");

        assertEquals(QuickFixMenu.State.FAILED, menu.state());
        assertEquals("malformed: bad payload", menu.message());
        assertEquals(Optional.empty(), menu.press(OverlayKey.ENTER));
    }

    @Test
    void theMenuStartsLoadingAndAnAnswerOfAnEarlierRequestIsIgnored() {
        QuickFixMenu menu = new QuickFixMenu();
        long stale = menu.begin();
        long current = menu.begin();

        menu.showActions(stale, List.of(FIRST));
        assertEquals(QuickFixMenu.State.LOADING, menu.state(), "a stale answer does not replace the loading state");

        menu.showActions(current, List.of(SECOND));
        assertEquals(List.of(SECOND), menu.actions());
    }

    @Test
    void escapeClosesTheMenuAndAnswersLandingAfterwardAreIgnored() {
        QuickFixMenu menu = menuShowing(FIRST);
        long ticket = menu.begin();

        menu.press(OverlayKey.ESCAPE);
        menu.showActions(ticket, List.of(SECOND));

        assertFalse(menu.isOpen());
        assertEquals(Optional.empty(), menu.press(OverlayKey.ENTER));
    }

    @Test
    void onlyActionsWithAnEditCanBeAppliedByTheEditor() {
        assertTrue(QuickFixMenu.appliesEdit(FIRST));
        assertFalse(QuickFixMenu.appliesEdit(COMMAND_ONLY));
    }

    @Test
    void aClickSelectsTheRowAndASecondClickOnTheSameRowApplies() {
        QuickFixMenu menu = menuShowing(FIRST, SECOND, COMMAND_ONLY);
        menu.layout(new OverlayRegion(10, 5, 30, 8), new OverlayRegion(11, 6, 28, 6));

        assertEquals(Optional.empty(), menu.pointer(12, 7, true, 0, 0L));
        assertEquals(1, menu.selectedIndex(), "the clicked row is selected");
        assertEquals(Optional.of(SECOND), menu.pointer(12, 7, true, 0, DoubleClickTracker.WINDOW_NANOS / 2));
    }

    @Test
    void aClickOutsideThePanelCloses() {
        QuickFixMenu menu = menuShowing(FIRST);
        menu.layout(new OverlayRegion(10, 5, 30, 8), new OverlayRegion(11, 6, 28, 6));

        menu.pointer(0, 0, true, 0, 0L);

        assertFalse(menu.isOpen());
    }

    private static QuickFixMenu menuShowing(CodeAction... actions) {
        QuickFixMenu menu = new QuickFixMenu();
        long ticket = menu.begin();
        menu.showActions(ticket, List.of(actions));
        return menu;
    }
}
