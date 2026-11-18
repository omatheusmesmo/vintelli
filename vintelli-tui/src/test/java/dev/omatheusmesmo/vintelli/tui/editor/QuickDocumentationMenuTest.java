package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Face;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Piece;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Row;

class QuickDocumentationMenuTest {

    private static final OverlayRegion INNER = new OverlayRegion(0, 0, 40, 3);

    @Test
    void aRequestStartsLoadingAndTheAnswerReplacesTheLoadingState() {
        QuickDocumentationMenu menu = new QuickDocumentationMenu();
        long ticket = menu.begin();

        assertEquals(QuickDocumentationMenu.State.LOADING, menu.view().state());
        menu.showDocumentation(ticket, List.of(row("int length()")));

        assertEquals(QuickDocumentationMenu.State.DOCUMENTED, menu.view().state());
        assertEquals(List.of(row("int length()")), menu.view().documentation());
    }

    @Test
    void anEmptyAnswerSaysThereIsNoDocumentation() {
        QuickDocumentationMenu menu = new QuickDocumentationMenu();
        long ticket = menu.begin();

        menu.showDocumentation(ticket, List.of());

        assertEquals(QuickDocumentationMenu.State.EMPTY, menu.view().state());
    }

    @Test
    void anUnavailableOrFailedServerKeepsItsReason() {
        QuickDocumentationMenu unavailable = new QuickDocumentationMenu();
        unavailable.showUnavailable(unavailable.begin(), "no healthy provider for 'hover': lsp");
        assertEquals(QuickDocumentationMenu.State.UNAVAILABLE, unavailable.view().state());
        assertEquals("no healthy provider for 'hover': lsp", unavailable.view().message());

        QuickDocumentationMenu failed = new QuickDocumentationMenu();
        failed.showFailure(failed.begin(), "the server closed the connection");
        assertEquals(QuickDocumentationMenu.State.FAILED, failed.view().state());
        assertEquals("the server closed the connection", failed.view().message());
    }

    @Test
    void anAnswerForAnEarlierRequestCannotReplaceTheNewerOne() {
        QuickDocumentationMenu menu = new QuickDocumentationMenu();
        long first = menu.begin();
        long second = menu.begin();

        menu.showDocumentation(first, List.of(row("stale")));
        assertEquals(QuickDocumentationMenu.State.LOADING, menu.view().state());

        menu.showDocumentation(second, List.of(row("current")));
        assertEquals(List.of(row("current")), menu.view().documentation());
    }

    @Test
    void anAnswerThatArrivesAfterTheMenuClosedIsIgnored() {
        QuickDocumentationMenu menu = new QuickDocumentationMenu();
        long ticket = menu.begin();
        menu.close();

        menu.showDocumentation(ticket, List.of(row("late")));

        assertFalse(menu.isOpen());
        assertEquals(QuickDocumentationMenu.State.LOADING, menu.view().state());
    }

    @Test
    void theArrowAndPageKeysScrollTheWrappedRowsAndStopAtBothEnds() {
        QuickDocumentationMenu menu = documented(10);
        menu.layout(INNER);

        menu.press(OverlayKey.DOWN);
        assertEquals(1, menu.view().firstRow());
        menu.press(OverlayKey.PAGE_DOWN);
        assertEquals(4, menu.view().firstRow(), "a page is the visible height");
        menu.press(OverlayKey.PAGE_DOWN);
        menu.press(OverlayKey.PAGE_DOWN);
        assertEquals(7, menu.view().firstRow(), "the last window stops at the end of the documentation");
        menu.press(OverlayKey.DOWN);
        assertEquals(7, menu.view().firstRow());
        menu.press(OverlayKey.UP);
        assertEquals(6, menu.view().firstRow());
        menu.press(OverlayKey.PAGE_UP);
        menu.press(OverlayKey.PAGE_UP);
        menu.press(OverlayKey.PAGE_UP);
        assertEquals(0, menu.view().firstRow(), "the first window stops at the top");
    }

    @Test
    void theWindowShowsTheRowsFromFirstRowOnlyAndTheWheelScrollsThree() {
        QuickDocumentationMenu menu = documented(10);
        menu.layout(INNER);

        menu.wheel(1);
        QuickDocumentationMenu.View view = menu.view();
        assertEquals(3, view.firstRow());
        assertEquals(3, view.visibleRows());
        assertEquals(List.of("row 3", "row 4", "row 5"), labelsOf(view.rows().subList(view.firstRow(),
                view.firstRow() + view.visibleRows())));
        menu.wheel(-1);
        assertEquals(0, menu.view().firstRow());
    }

    @Test
    void theRowsWrapToTheWidthOfThePanelAndTheWindowStaysInRange() {
        QuickDocumentationMenu menu = new QuickDocumentationMenu();
        menu.showDocumentation(menu.begin(), List.of(row("alpha beta gamma delta")));

        menu.layout(new OverlayRegion(0, 0, 10, 2));
        assertEquals(List.of("alpha beta", "gamma", "delta"), labelsOf(menu.view().rows()));

        menu.layout(new OverlayRegion(0, 0, 11, 2));
        assertEquals(List.of("alpha beta", "gamma delta"), labelsOf(menu.view().rows()));

        menu.layout(new OverlayRegion(0, 0, 40, 2));
        assertEquals(List.of("alpha beta gamma delta"), labelsOf(menu.view().rows()));
        assertEquals(0, menu.view().firstRow());
    }

    @Test
    void theMenuTakesOnlyEscapeAndTheScrollKeysAndClosesOnEscape() {
        QuickDocumentationMenu menu = documented(10);

        assertTrue(menu.takes(OverlayKey.ESCAPE));
        assertTrue(menu.takes(OverlayKey.UP));
        assertTrue(menu.takes(OverlayKey.DOWN));
        assertTrue(menu.takes(OverlayKey.PAGE_UP));
        assertTrue(menu.takes(OverlayKey.PAGE_DOWN));
        assertFalse(menu.takes(OverlayKey.ENTER), "Enter is typed text for the editor, the popup does not take it");
        assertFalse(menu.takes(OverlayKey.TEXT));
        assertFalse(menu.takes(OverlayKey.BACKSPACE));

        menu.press(OverlayKey.ESCAPE);
        assertFalse(menu.isOpen());
        assertFalse(menu.takes(OverlayKey.ESCAPE), "a closed popup takes no key");
    }

    @Test
    void aLoadingOrEmptyPopupHasNoRowsToScroll() {
        QuickDocumentationMenu menu = new QuickDocumentationMenu();
        menu.begin();
        menu.press(OverlayKey.DOWN);
        menu.layout(INNER);

        assertEquals(0, menu.view().firstRow());
        assertEquals(List.of(), menu.view().rows());
    }

    private static QuickDocumentationMenu documented(int count) {
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(row("row " + index));
        }
        QuickDocumentationMenu menu = new QuickDocumentationMenu();
        menu.showDocumentation(menu.begin(), rows);
        return menu;
    }

    private static Row row(String text) {
        return new Row(List.of(new Piece(text, Face.PLAIN)));
    }

    private static List<String> labelsOf(List<Row> rows) {
        return rows.stream().map(row -> row.pieces().stream().map(Piece::text).reduce("", String::concat)).toList();
    }
}
