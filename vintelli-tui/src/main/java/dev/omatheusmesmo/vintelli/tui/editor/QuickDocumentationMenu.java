package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.List;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

/**
 * State and rules of the quick documentation popup: the request state, the documentation the server answered, the
 * rows wrapped to the width of the panel, and the window of rows that is shown. The popup keeps only the keys that
 * scroll it and Escape; every other key closes it, which the shell decides with {@link #takes(OverlayKey)}.
 * Answers are matched to the request by a ticket, so an answer of an earlier request cannot replace a newer one.
 */
final class QuickDocumentationMenu {

    static final int WHEEL_STEP = 3;

    enum State { LOADING, DOCUMENTED, EMPTY, UNAVAILABLE, FAILED }

    /**
     * What the overlay draws: the state, the reason text, the documentation rows as answered, the rows wrapped to the
     * panel, and the window of wrapped rows that starts at {@code firstRow}.
     */
    record View(State state, String message, List<HoverText.Row> documentation, List<HoverText.Row> rows,
            int firstRow, int visibleRows) {
    }

    private boolean open;
    private long ticket;
    private State state = State.LOADING;
    private String message = "";
    private List<HoverText.Row> documentation = List.of();
    private List<HoverText.Row> rows = List.of();
    private int firstRow;
    private int visibleRows;

    synchronized long begin() {
        open = true;
        ticket++;
        state = State.LOADING;
        message = "";
        documentation = List.of();
        rows = List.of();
        firstRow = 0;
        return ticket;
    }

    synchronized void close() {
        open = false;
    }

    synchronized boolean isOpen() {
        return open;
    }

    synchronized long ticket() {
        return ticket;
    }

    /**
     * @return whether an answer for the ticket is still awaited by the open popup
     */
    synchronized boolean awaits(long answer) {
        return open && answer == ticket && state == State.LOADING;
    }

    synchronized void showDocumentation(long answer, List<HoverText.Row> found) {
        if (!awaits(answer)) {
            return;
        }
        documentation = List.copyOf(found);
        state = documentation.isEmpty() ? State.EMPTY : State.DOCUMENTED;
        rows = List.of();
        firstRow = 0;
    }

    synchronized void showUnavailable(long answer, String reason) {
        finish(answer, State.UNAVAILABLE, reason);
    }

    synchronized void showFailure(long answer, String reason) {
        finish(answer, State.FAILED, reason);
    }

    /**
     * @return whether the open popup takes the key: Escape closes it and the arrow and page keys scroll it
     */
    synchronized boolean takes(OverlayKey key) {
        return open && switch (key) {
            case ESCAPE, UP, DOWN, PAGE_UP, PAGE_DOWN -> true;
            default -> false;
        };
    }

    synchronized void press(OverlayKey key) {
        if (!open) {
            return;
        }
        switch (key) {
            case ESCAPE -> close();
            case UP -> scroll(-1);
            case DOWN -> scroll(1);
            case PAGE_UP -> scroll(-Math.max(1, visibleRows));
            case PAGE_DOWN -> scroll(Math.max(1, visibleRows));
            default -> {
            }
        }
    }

    /**
     * A wheel move scrolls the documentation by a few rows.
     *
     * @param direction negative for a wheel up, positive for a wheel down, zero for nothing
     */
    synchronized void wheel(int direction) {
        scroll(Integer.signum(direction) * WHEEL_STEP);
    }

    /**
     * Records the inner rows of the panel: the documentation is wrapped to their width and the window is kept inside
     * the wrapped rows.
     */
    synchronized void layout(OverlayRegion inner) {
        visibleRows = Math.max(0, inner.height());
        rows = state == State.DOCUMENTED ? HoverText.wrap(documentation, inner.width()) : List.of();
        firstRow = clampedFirstRow(firstRow);
    }

    synchronized View view() {
        return new View(state, message, documentation, rows, firstRow, visibleRows);
    }

    private void finish(long answer, State finished, String reason) {
        if (!awaits(answer)) {
            return;
        }
        state = finished;
        message = reason;
    }

    private void scroll(int lines) {
        if (open && state == State.DOCUMENTED) {
            firstRow = clampedFirstRow(firstRow + lines);
        }
    }

    private int clampedFirstRow(int candidate) {
        return Math.clamp(candidate, 0, Math.max(0, rows.size() - visibleRows));
    }
}
