package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * State and rules of the command palette: the filter, the selection, the scroll
 * window and the pointer hits. It holds no TamboUI type; the palette draws the
 * rows it reads and forwards the events. The window is written by
 * {@link #layout}, which only the render pass calls.
 */
final class CommandPaletteController {

    private final List<KeyAction> actions;
    private final List<KeyAction> filtered = new ArrayList<>();
    private final DoubleClickTracker doubleClick = new DoubleClickTracker();
    private String filter = "";
    private int selected;
    private boolean open;
    private OverlayRegion panel = OverlayRegion.EMPTY;
    private OverlayRegion inner = OverlayRegion.EMPTY;
    private int visibleRows;
    private int scrollOffset;
    private int renderedFirst;
    private List<KeyAction> renderedActions = List.of();

    CommandPaletteController(List<KeyAction> actions) {
        this.actions = List.copyOf(actions);
    }

    void openWith(String initialFilter) {
        open = true;
        filter = initialFilter;
        selected = 0;
        scrollOffset = 0;
        doubleClick.reset();
        refresh();
    }

    void close() {
        open = false;
        panel = OverlayRegion.EMPTY;
        renderedActions = List.of();
        doubleClick.reset();
    }

    boolean isOpen() {
        return open;
    }

    String filter() {
        return filter;
    }

    int matchCount() {
        return filtered.size();
    }

    int selectedIndex() {
        return selected;
    }

    int firstVisible() {
        return renderedFirst;
    }

    List<KeyAction> visibleActions() {
        return renderedActions;
    }

    /**
     * Consumes the key while the palette is open and returns the action the
     * user confirmed with Enter, if any; every other key is swallowed as filter
     * input, selection movement or an ignored key.
     */
    Optional<KeyAction> press(OverlayKey key, int codePoint) {
        if (!open) {
            return Optional.empty();
        }
        return switch (key) {
            case ESCAPE -> {
                close();
                yield Optional.empty();
            }
            case ENTER -> filtered.isEmpty() ? Optional.empty() : Optional.of(filtered.get(selected));
            case UP -> {
                selected = Math.max(0, selected - 1);
                keepSelectionVisible();
                yield Optional.empty();
            }
            case DOWN -> {
                selected = Math.min(Math.max(0, filtered.size() - 1), selected + 1);
                keepSelectionVisible();
                yield Optional.empty();
            }
            case TEXT -> {
                filter += Character.toString(codePoint);
                editFilter();
                yield Optional.empty();
            }
            case BACKSPACE -> {
                filter = filter.isEmpty() ? "" : filter.substring(0, filter.length() - 1);
                editFilter();
                yield Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    /**
     * Handles pointer input for the visible palette. A scroll is -1 for a wheel
     * up, 1 for a wheel down and 0 otherwise; a second click on the same row
     * within the double click window confirms it.
     */
    Optional<KeyAction> pointer(int x, int y, boolean click, int scroll, long nanos) {
        if (!open) {
            return Optional.empty();
        }
        if (click && !panel.contains(x, y)) {
            close();
            return Optional.empty();
        }
        if (!panel.contains(x, y) || !renderedActions.equals(currentVisibleActions())) {
            return Optional.empty();
        }
        if (scroll != 0) {
            wheel(x, y, scroll);
            return Optional.empty();
        }
        if (!click || y < inner.y() + 1 || !inner.contains(x, y)) {
            if (click) {
                doubleClick.reset();
            }
            return Optional.empty();
        }
        int row = y - inner.y() - 1;
        if (row < 0 || row >= renderedActions.size() || x < inner.x() || x >= inner.x() + inner.width()) {
            doubleClick.reset();
            return Optional.empty();
        }
        int index = renderedFirst + row;
        selected = index;
        return doubleClick.register(index, nanos) ? Optional.of(filtered.get(index)) : Optional.empty();
    }

    /**
     * Called by the render pass once the panel is placed: records the panel and
     * its inner rows for hit testing, and writes the scroll window that the
     * visible rows are read from.
     */
    void layout(OverlayRegion panel, OverlayRegion inner) {
        this.panel = panel;
        this.inner = inner;
        this.visibleRows = Math.max(0, inner.height() - 1);
        renderWindow();
    }

    private void editFilter() {
        refresh();
        scrollOffset = 0;
    }

    private void wheel(int x, int y, int direction) {
        doubleClick.reset();
        if (!inner.contains(x, y) || y < inner.y() + 1 || renderedActions.isEmpty()) {
            return;
        }
        scrollOffset = Math.clamp(scrollOffset + direction * 3, 0, Math.max(0, filtered.size() - visibleRows));
        selected = Math.clamp(selected, scrollOffset,
                Math.min(filtered.size() - 1, scrollOffset + Math.max(1, visibleRows) - 1));
    }

    private void renderWindow() {
        if (filtered.isEmpty()) {
            renderedFirst = 0;
            renderedActions = List.of();
            return;
        }
        int first = Math.clamp(scrollOffset, 0, Math.max(0, filtered.size() - visibleRows));
        if (selected < first) {
            first = selected;
        } else if (visibleRows > 0 && selected >= first + visibleRows) {
            first = selected - visibleRows + 1;
        }
        scrollOffset = first;
        renderedFirst = first;
        int rows = Math.max(0, Math.min(visibleRows, filtered.size() - first));
        renderedActions = List.copyOf(filtered.subList(first, first + rows));
    }

    private void refresh() {
        String query = filter.toLowerCase();
        filtered.clear();
        for (KeyAction action : actions) {
            if (action.label().toLowerCase().contains(query) || action.id().toLowerCase().contains(query)) {
                filtered.add(action);
            }
        }
        if (selected >= filtered.size()) {
            selected = Math.max(0, filtered.size() - 1);
        }
        keepSelectionVisible();
    }

    private void keepSelectionVisible() {
        if (visibleRows == 0) {
            return;
        }
        if (selected < scrollOffset) {
            scrollOffset = selected;
        } else if (selected >= scrollOffset + visibleRows) {
            scrollOffset = selected - visibleRows + 1;
        }
    }

    private List<KeyAction> currentVisibleActions() {
        int end = Math.min(filtered.size(), renderedFirst + renderedActions.size());
        return renderedFirst < 0 || renderedFirst >= end ? List.of()
                : List.copyOf(filtered.subList(renderedFirst, end));
    }
}
