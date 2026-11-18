package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Kind;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionMenuTest {

    private static final Suggestion LENGTH = item("length", Kind.METHOD, "int");
    private static final Suggestion LAST = item("lastIndexOf", Kind.METHOD, "int");
    private static final Suggestion TEXT = item("text", Kind.FIELD, "String");

    @Test
    void theRequestStartsLoadingAndAnAnswerOfThatRequestShowsItsRows() {
        CompletionMenu menu = new CompletionMenu();
        long ticket = menu.begin();

        assertEquals(CompletionMenu.State.LOADING, menu.state());
        menu.showSuggestions(ticket, List.of(LENGTH, LAST));

        assertEquals(CompletionMenu.State.ITEMS, menu.state());
        assertEquals(List.of(LENGTH, LAST), menu.rows());
        assertEquals(Optional.of(LENGTH), menu.selected());
    }

    @Test
    void anAnswerForAnEarlierRequestCannotReplaceTheNewerOne() {
        CompletionMenu menu = new CompletionMenu();
        long first = menu.begin();
        long second = menu.begin();

        menu.showSuggestions(first, List.of(LENGTH));
        assertEquals(CompletionMenu.State.LOADING, menu.state(), "the older ticket is ignored");

        menu.showSuggestions(second, List.of(TEXT));
        assertEquals(List.of(TEXT), menu.rows());
    }

    @Test
    void noSuggestionsIsItsOwnStateAndEnterInsertsNothing() {
        CompletionMenu menu = new CompletionMenu();
        long ticket = menu.begin();

        menu.showSuggestions(ticket, List.of());

        assertEquals(CompletionMenu.State.EMPTY, menu.state());
        assertEquals(Optional.empty(), menu.press(OverlayKey.ENTER));
        assertTrue(menu.isOpen(), "an empty answer leaves the popup open to say so");
    }

    @Test
    void anUnavailableOrFailedServerKeepsItsReason() {
        CompletionMenu menu = new CompletionMenu();
        long ticket = menu.begin();

        menu.showUnavailable(ticket, "no language server profile is configured");
        assertEquals(CompletionMenu.State.UNAVAILABLE, menu.state());
        assertEquals("no language server profile is configured", menu.view().message());

        long next = menu.begin();
        menu.showFailure(next, "the server closed the connection");
        assertEquals(CompletionMenu.State.FAILED, menu.state());
        assertEquals("the server closed the connection", menu.view().message());
    }

    @Test
    void enterConfirmsTheSelectedRowAndEscapeClosesThePopup() {
        CompletionMenu menu = new CompletionMenu();
        menu.showSuggestions(menu.begin(), List.of(LENGTH, LAST));

        assertEquals(Optional.empty(), menu.press(OverlayKey.DOWN));
        assertEquals(1, menu.selectedIndex());
        assertEquals(Optional.of(LAST), menu.press(OverlayKey.ENTER));

        assertEquals(Optional.empty(), menu.press(OverlayKey.ESCAPE));
        assertFalse(menu.isOpen());
    }

    @Test
    void theSelectionStopsAtTheEndsInsteadOfWrapping() {
        CompletionMenu menu = new CompletionMenu();
        menu.showSuggestions(menu.begin(), List.of(LENGTH, LAST));

        menu.press(OverlayKey.UP);
        assertEquals(0, menu.selectedIndex());
        menu.press(OverlayKey.DOWN);
        menu.press(OverlayKey.DOWN);
        assertEquals(1, menu.selectedIndex());
    }

    @Test
    void pageKeysMoveBySeenRowsAndTheSelectionStaysInView() {
        CompletionMenu menu = new CompletionMenu();
        List<Suggestion> many = IntStream.range(0, 30)
                .mapToObj(index -> item("item" + index, Kind.METHOD, "")).toList();
        menu.showSuggestions(menu.begin(), many);
        menu.layout(new OverlayRegion(0, 0, 30, 12), new OverlayRegion(1, 1, 28, 4));

        menu.press(OverlayKey.PAGE_DOWN);
        assertEquals(4, menu.selectedIndex());
        assertEquals(1, menu.view().firstVisible(), "the selected row is the last visible row");
    }

    @Test
    void aTypedPrefixNarrowsTheRowsAndAMissAfterThatHasNothingToShow() {
        CompletionMenu menu = new CompletionMenu();
        menu.showSuggestions(menu.begin(), List.of(LENGTH, LAST, TEXT));

        assertTrue(menu.filter("l"));
        assertEquals(List.of(LENGTH, LAST), menu.rows());
        assertEquals(0, menu.selectedIndex(), "a new prefix selects the best match again");

        assertFalse(menu.filter("zz"), "no match leaves the menu with nothing to show");
        assertTrue(menu.rows().isEmpty());
    }

    @Test
    void aClickOnARowSelectsAndConfirmsIt() {
        CompletionMenu menu = new CompletionMenu();
        menu.showSuggestions(menu.begin(), List.of(LENGTH, LAST));
        menu.layout(new OverlayRegion(0, 0, 30, 4), new OverlayRegion(1, 1, 28, 2));

        assertEquals(Optional.empty(), menu.click(5, 0), "a click on the border misses the rows");
        assertEquals(Optional.of(LAST), menu.click(5, 2));
        assertTrue(menu.contains(5, 2));
        assertFalse(menu.contains(40, 40));
    }

    private static Suggestion item(String label, Kind kind, String detail) {
        return new Suggestion(label, kind, detail, null, null, false, null, null);
    }
}
