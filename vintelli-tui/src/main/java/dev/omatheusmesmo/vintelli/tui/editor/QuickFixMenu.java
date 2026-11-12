package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

import java.util.List;
import java.util.Optional;

/**
 * State and rules of the quick fix popup: the request state, the actions it lists, the selection with
 * wrap-around, the pointer hits and the notice of the last Enter. It holds no TamboUI type; the overlay
 * draws the {@link View} and forwards the events. Answers are matched to the request by a ticket, so an
 * answer of an earlier request cannot replace the state of a newer one.
 */
final class QuickFixMenu {

    static final int MAX_ROWS = 8;
    static final int FOOTER_ROWS = 2;

    enum State { LOADING, ACTIONS, EMPTY, UNAVAILABLE, FAILED }

    /**
     * What the overlay draws: the state, the reason or notice text, every action and the window of rows that
     * starts at {@code firstVisible}.
     */
    record View(State state, String message, String notice, List<CodeAction> actions, int selected, int firstVisible) {

        int bodyRows() {
            return state == State.ACTIONS ? Math.min(actions.size(), MAX_ROWS) : 1;
        }

        List<CodeAction> visible() {
            if (state != State.ACTIONS) {
                return List.of();
            }
            return actions.subList(firstVisible, Math.min(actions.size(), firstVisible + bodyRows()));
        }
    }

    private final DoubleClickTracker doubleClick = new DoubleClickTracker();
    private boolean open;
    private long ticket;
    private State state = State.LOADING;
    private String message = "";
    private String notice = "";
    private List<CodeAction> actions = List.of();
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
        notice = "";
        actions = List.of();
        selected = 0;
        firstVisible = 0;
        doubleClick.reset();
        return ticket;
    }

    synchronized void close() {
        open = false;
        panel = OverlayRegion.EMPTY;
        inner = OverlayRegion.EMPTY;
        doubleClick.reset();
    }

    synchronized boolean isOpen() {
        return open;
    }

    synchronized State state() {
        return state;
    }

    synchronized List<CodeAction> actions() {
        return actions;
    }

    synchronized int selectedIndex() {
        return selected;
    }

    synchronized Optional<CodeAction> selected() {
        return state == State.ACTIONS && !actions.isEmpty() ? Optional.of(actions.get(selected)) : Optional.empty();
    }

    synchronized String message() {
        return message;
    }

    synchronized void say(String text) {
        notice = text;
    }

    synchronized void showActions(long answer, List<CodeAction> found) {
        if (!answers(answer)) {
            return;
        }
        actions = List.copyOf(found);
        state = actions.isEmpty() ? State.EMPTY : State.ACTIONS;
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
     * Handles one key of the open popup.
     *
     * @return the action Enter confirmed; empty for every other key and when nothing is selected
     */
    synchronized Optional<CodeAction> press(OverlayKey key) {
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
            default -> {
            }
        }
        return Optional.empty();
    }

    /**
     * Handles pointer input for the popup: a click outside it closes it, a click on a row selects the row and
     * a second click on the same row within the double click window confirms it, and a scroll moves the selection.
     *
     * @return the action the second click confirmed; empty otherwise
     */
    synchronized Optional<CodeAction> pointer(int x, int y, boolean click, int scroll, long nanos) {
        if (!open) {
            return Optional.empty();
        }
        if (click && !panel.contains(x, y)) {
            close();
            return Optional.empty();
        }
        if (!panel.contains(x, y) || state != State.ACTIONS) {
            return Optional.empty();
        }
        if (scroll != 0) {
            doubleClick.reset();
            move(scroll);
            return Optional.empty();
        }
        if (!click || !inner.contains(x, y)) {
            return Optional.empty();
        }
        int row = y - inner.y();
        int index = firstVisible + row;
        if (row < 0 || row >= visibleRows || index >= actions.size()) {
            doubleClick.reset();
            return Optional.empty();
        }
        selected = index;
        return doubleClick.register(index, nanos) ? Optional.of(actions.get(index)) : Optional.empty();
    }

    /**
     * Records the placed panel and its inner rows for hit testing, and the number of action rows that fit.
     */
    synchronized void layout(OverlayRegion placed, OverlayRegion innerRows) {
        panel = placed;
        inner = innerRows;
        visibleRows = Math.max(0, innerRows.height() - FOOTER_ROWS);
        keepSelectionVisible();
    }

    synchronized View view() {
        return new View(state, message, notice, actions, selected, firstVisible);
    }

    /**
     * Whether an action carries a workspace edit the editor can apply. An action without one is a command
     * only action, which Vintelli cannot run yet.
     */
    static boolean appliesEdit(CodeAction action) {
        return action.edit() != null;
    }

    private boolean answers(long answer) {
        return open && answer == ticket && state == State.LOADING;
    }

    private void finish(long answer, State finished, String reason) {
        if (!answers(answer)) {
            return;
        }
        actions = List.of();
        state = finished;
        message = reason;
    }

    private void move(int step) {
        if (actions.isEmpty()) {
            return;
        }
        selected = Math.floorMod(selected + step, actions.size());
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
        firstVisible = Math.clamp(firstVisible, 0, Math.max(0, actions.size() - visibleRows));
    }
}
