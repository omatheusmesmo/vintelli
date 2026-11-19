package dev.omatheusmesmo.vintelli.tui.editor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

/**
 * State and rules of the quick definition popup: the request state, the declarations the server resolved, the one
 * that is shown and the window of its lines. The popup keeps the keys that scroll it, Enter that opens the shown
 * declaration and Escape; every other key closes it, which the shell decides with {@link #takes(OverlayKey)}.
 * Answers are matched to the request by a ticket, so an answer of an earlier request cannot replace a newer one.
 */
final class QuickDefinitionMenu {

    static final int WHEEL_STEP = 3;

    enum State { LOADING, PEEKING, FAILED }

    /**
     * One declaration: the file, the zero based line the target points at, the zero based line the shown text starts
     * at, and the shown lines.
     */
    record Peek(Path file, int line, int startLine, List<String> lines) {

        Peek {
            lines = List.copyOf(lines);
        }

        String title() {
            return file.getFileName() + ":" + (line + 1);
        }

        String code() {
            return String.join("\n", lines);
        }
    }

    /**
     * What the overlay draws: the state, the reason text, every declaration, the index of the shown one, and the
     * window of its lines that starts at {@code firstRow}.
     */
    record View(State state, String message, List<Peek> peeks, int index, int firstRow, int visibleRows) {

        Peek current() {
            return peeks.isEmpty() ? null : peeks.get(index);
        }
    }

    private boolean open;
    private long ticket;
    private State state = State.LOADING;
    private String message = "";
    private List<Peek> peeks = List.of();
    private int index;
    private int firstRow;
    private int visibleRows;

    synchronized long begin() {
        open = true;
        ticket++;
        state = State.LOADING;
        message = "";
        peeks = List.of();
        index = 0;
        firstRow = 0;
        return ticket;
    }

    synchronized void close() {
        open = false;
    }

    synchronized boolean isOpen() {
        return open;
    }

    /**
     * @return whether an answer for the ticket is still awaited by the open popup
     */
    synchronized boolean awaits(long answer) {
        return open && answer == ticket && state == State.LOADING;
    }

    synchronized void showPeeks(long answer, List<Peek> found) {
        if (!awaits(answer)) {
            return;
        }
        peeks = List.copyOf(found);
        state = peeks.isEmpty() ? State.FAILED : State.PEEKING;
        message = peeks.isEmpty() ? "No definition found" : "";
        index = 0;
        firstRow = 0;
    }

    synchronized void showFailure(long answer, String reason) {
        if (!awaits(answer)) {
            return;
        }
        state = State.FAILED;
        message = reason;
    }

    /**
     * @return whether the open popup takes the key: Escape closes it, Enter opens the shown declaration and the
     *         arrow and page keys scroll it
     */
    synchronized boolean takes(OverlayKey key) {
        return open && switch (key) {
            case ESCAPE, ENTER, UP, DOWN, PAGE_UP, PAGE_DOWN -> true;
            default -> false;
        };
    }

    /**
     * @return the shown declaration when the key opens it, which also closes the popup
     */
    synchronized Optional<Peek> press(OverlayKey key) {
        if (!open) {
            return Optional.empty();
        }
        switch (key) {
            case ESCAPE -> close();
            case ENTER -> {
                return accept();
            }
            case UP -> scroll(-1);
            case DOWN -> scroll(1);
            case PAGE_UP -> scroll(-Math.max(1, visibleRows));
            case PAGE_DOWN -> scroll(Math.max(1, visibleRows));
            default -> {
            }
        }
        return Optional.empty();
    }

    /**
     * Opens the shown declaration: the popup closes and the declaration is handed back. A popup that shows no
     * declaration just closes.
     */
    synchronized Optional<Peek> accept() {
        Peek shown = open && state == State.PEEKING ? peeks.get(index) : null;
        close();
        return Optional.ofNullable(shown);
    }

    synchronized void next() {
        page(1);
    }

    synchronized void previous() {
        page(-1);
    }

    /**
     * A wheel move scrolls the shown lines by a few rows.
     *
     * @param direction negative for a wheel up, positive for a wheel down, zero for nothing
     */
    synchronized void wheel(int direction) {
        scroll(Integer.signum(direction) * WHEEL_STEP);
    }

    /**
     * Records the inner rows of the panel: the first one is the header, the others show the lines, and the window
     * is kept inside the shown lines.
     */
    synchronized void layout(OverlayRegion inner) {
        visibleRows = Math.max(0, inner.height() - 1);
        firstRow = clampedFirstRow(firstRow);
    }

    synchronized View view() {
        return new View(state, message, peeks, index, firstRow, visibleRows);
    }

    private void page(int step) {
        if (open && state == State.PEEKING && peeks.size() > 1) {
            index = Math.floorMod(index + step, peeks.size());
            firstRow = 0;
        }
    }

    private void scroll(int lines) {
        if (open && state == State.PEEKING) {
            firstRow = clampedFirstRow(firstRow + lines);
        }
    }

    private int clampedFirstRow(int candidate) {
        int count = state == State.PEEKING ? peeks.get(index).lines().size() : 0;
        return Math.clamp(candidate, 0, Math.max(0, count - visibleRows));
    }
}
