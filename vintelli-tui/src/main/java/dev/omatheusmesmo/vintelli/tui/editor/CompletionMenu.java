package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

import java.util.List;
import java.util.Optional;

/**
 * State and rules of the completion popup: the request state, the suggestions the server answered, the rows
 * that match the typed prefix, the selection and the pointer hits. The editor keeps the keyboard while the
 * popup is open, so the menu never reads text itself; {@link CompletionController} tells it the prefix. Answers
 * are matched to the request by a ticket, so an answer of an earlier request cannot replace a newer one.
 */
final class CompletionMenu {

    static final int MAX_ROWS = 10;

    enum State { LOADING, ITEMS, EMPTY, UNAVAILABLE, FAILED }

    /**
     * What the overlay draws: the state, the reason text, the rows that match and the window of rows that starts
     * at {@code firstVisible}.
     */
    record View(State state, String message, List<Suggestion> rows, int selected, int firstVisible) {

        int bodyRows() {
            return state == State.ITEMS ? Math.min(rows.size(), MAX_ROWS) : 1;
        }

        List<Suggestion> visible() {
            if (state != State.ITEMS) {
                return List.of();
            }
            return rows.subList(firstVisible, Math.min(rows.size(), firstVisible + bodyRows()));
        }
    }

    private boolean open;
    private long ticket;
    private State state = State.LOADING;
    private String message = "";
    private List<Suggestion> suggestions = List.of();
    private List<Suggestion> rows = List.of();
    private String prefix = "";
    private int selected;
    private int firstVisible;
    private int visibleRows;
    private OverlayRegion panel = OverlayRegion.EMPTY;
    private OverlayRegion inner = OverlayRegion.EMPTY;

    /**
     * Opens the popup in the loading state for a new request.
     *
     * @return the ticket the answer of this request has to carry
     */
    synchronized long begin() {
        open = true;
        ticket++;
        state = State.LOADING;
        message = "";
        suggestions = List.of();
        rows = List.of();
        prefix = "";
        selected = 0;
        firstVisible = 0;
        return ticket;
    }

    synchronized void close() {
        open = false;
        panel = OverlayRegion.EMPTY;
        inner = OverlayRegion.EMPTY;
    }

    synchronized boolean isOpen() {
        return open;
    }

    synchronized long ticket() {
        return ticket;
    }

    synchronized State state() {
        return state;
    }

    synchronized List<Suggestion> rows() {
        return rows;
    }

    synchronized int selectedIndex() {
        return selected;
    }

    synchronized Optional<Suggestion> selected() {
        return state == State.ITEMS && !rows.isEmpty() ? Optional.of(rows.get(selected)) : Optional.empty();
    }

    /**
     * @return whether an answer for the ticket is still awaited by the open popup
     */
    synchronized boolean awaits(long answer) {
        return open && answer == ticket && state == State.LOADING;
    }

    synchronized void showSuggestions(long answer, List<Suggestion> found) {
        if (!awaits(answer)) {
            return;
        }
        suggestions = List.copyOf(found);
        state = suggestions.isEmpty() ? State.EMPTY : State.ITEMS;
        rows = suggestions;
        selected = 0;
        firstVisible = 0;
    }

    synchronized void showUnavailable(long answer, String reason) {
        finish(answer, State.UNAVAILABLE, reason);
    }

    synchronized void showFailure(long answer, String reason) {
        finish(answer, State.FAILED, reason);
    }

    /**
     * Narrows the rows to the suggestions that match the prefix. The selection goes back to the best match
     * whenever the prefix changes.
     *
     * @return false when the answer has suggestions and none matches the prefix, so the popup has nothing to show
     */
    synchronized boolean filter(String typed) {
        if (state != State.ITEMS) {
            prefix = typed;
            return true;
        }
        if (!typed.equals(prefix)) {
            prefix = typed;
            rows = CompletionFilter.rank(suggestions, typed);
            selected = 0;
            firstVisible = 0;
        }
        return !rows.isEmpty();
    }

    /**
     * Handles one key the popup takes while it is open. Enter confirms the selected row; Escape closes the popup.
     *
     * @return the suggestion Enter confirmed; empty for every other key and when nothing is selected
     */
    synchronized Optional<Suggestion> press(OverlayKey key) {
        if (!open) {
            return Optional.empty();
        }
        switch (key) {
            case ESCAPE -> close();
            case ENTER -> {
                return selected();
            }
            case UP -> move(-1);
            case DOWN -> move(1);
            case PAGE_UP -> move(-Math.max(1, visibleRows));
            case PAGE_DOWN -> move(Math.max(1, visibleRows));
            default -> {
            }
        }
        return Optional.empty();
    }

    /**
     * A click on a row selects it and confirms it, so a click inserts the suggestion.
     *
     * @return the suggestion the click confirmed; empty when the click missed the rows
     */
    synchronized Optional<Suggestion> click(int x, int y) {
        if (!open || state != State.ITEMS || !inner.contains(x, y)) {
            return Optional.empty();
        }
        int index = firstVisible + (y - inner.y());
        if (y - inner.y() < 0 || y - inner.y() >= visibleRows || index >= rows.size()) {
            return Optional.empty();
        }
        selected = index;
        return Optional.of(rows.get(index));
    }

    /**
     * A wheel move over the popup moves the selection.
     */
    synchronized void scroll(int direction) {
        if (open && state == State.ITEMS && direction != 0) {
            move(direction);
        }
    }

    synchronized boolean contains(int x, int y) {
        return open && panel.contains(x, y);
    }

    /**
     * Records the placed panel and its inner rows for hit testing, and the number of rows that fit.
     */
    synchronized void layout(OverlayRegion placed, OverlayRegion innerRows) {
        panel = placed;
        inner = innerRows;
        visibleRows = Math.max(0, innerRows.height());
        keepSelectionVisible();
    }

    synchronized View view() {
        return new View(state, message, rows, selected, firstVisible);
    }

    private void finish(long answer, State finished, String reason) {
        if (!awaits(answer)) {
            return;
        }
        suggestions = List.of();
        rows = List.of();
        state = finished;
        message = reason;
    }

    private void move(int step) {
        if (rows.isEmpty()) {
            return;
        }
        selected = Math.clamp((long) selected + step, 0, rows.size() - 1);
        keepSelectionVisible();
    }

    private void keepSelectionVisible() {
        if (visibleRows <= 0) {
            return;
        }
        if (selected < firstVisible) {
            firstVisible = selected;
        } else if (selected >= firstVisible + visibleRows) {
            firstVisible = selected - visibleRows + 1;
        }
        firstVisible = Math.clamp(firstVisible, 0, Math.max(0, rows.size() - visibleRows));
    }
}
