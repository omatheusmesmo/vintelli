package dev.omatheusmesmo.vintelli.tui.navigation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

/**
 * State and rules of the navigation popup that Go to Definition and Find Usages open when a symbol resolves to more
 * than one place: the titled list of targets, the filter typed over it, the selection and the pointer hits. It holds
 * no TamboUI type; {@link NavigationTargetsOverlay} draws the {@link View} and forwards the events. Enter or a click
 * on a row confirms it and closes the popup, Escape closes it, and typing narrows the rows to the ones whose file,
 * directory or source line contain the text.
 */
public final class NavigationTargets {

    /** The most rows the popup shows before it scrolls. */
    public static final int MAX_ROWS = 10;

    /**
     * What the overlay draws: the title, the filter, the rows that match it, and the window of {@code visibleRows} of
     * them that starts at {@code firstVisible}.
     */
    public record View(String title, String filter, List<NavigationTarget> rows, int selected, int firstVisible,
            int visibleRows, int total) {

        public int bodyRows() {
            return Math.max(1, Math.min(rows.size(), MAX_ROWS));
        }

        public int headerRows() {
            return filter.isEmpty() ? 0 : 1;
        }

        public List<NavigationTarget> visible() {
            return rows.subList(firstVisible, Math.min(rows.size(), firstVisible + visibleRows));
        }
    }

    private boolean open;
    private String title = "";
    private List<NavigationTarget> all = List.of();
    private List<NavigationTarget> rows = List.of();
    private String filter = "";
    private int selected;
    private int firstVisible;
    private int visibleRows = MAX_ROWS;
    private OverlayRegion panel = OverlayRegion.EMPTY;
    private OverlayRegion inner = OverlayRegion.EMPTY;

    public synchronized void open(String title, List<NavigationTarget> targets) {
        this.title = Objects.requireNonNull(title, "title");
        all = List.copyOf(targets);
        rows = all;
        filter = "";
        selected = 0;
        firstVisible = 0;
        visibleRows = MAX_ROWS;
        open = true;
    }

    public synchronized void close() {
        open = false;
        panel = OverlayRegion.EMPTY;
        inner = OverlayRegion.EMPTY;
    }

    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized View view() {
        return new View(title, filter, rows, selected, firstVisible, visibleRows, all.size());
    }

    /**
     * Handles one key of the open popup.
     *
     * @return the target Enter confirmed, which also closed the popup; empty for every other key
     */
    public synchronized Optional<NavigationTarget> press(OverlayKey key, int codePoint) {
        if (!open) {
            return Optional.empty();
        }
        switch (key) {
            case ESCAPE -> close();
            case ENTER -> {
                return confirm();
            }
            case UP -> move(-1);
            case DOWN -> move(1);
            case PAGE_UP -> move(-MAX_ROWS);
            case PAGE_DOWN -> move(MAX_ROWS);
            case BACKSPACE -> backspace();
            case TEXT -> type(codePoint);
            default -> {
            }
        }
        return Optional.empty();
    }

    /**
     * Handles pointer input: a click outside the popup closes it, a click on a row confirms it and the wheel moves
     * the selection.
     *
     * @return the target the click confirmed, which also closed the popup; empty otherwise
     */
    public synchronized Optional<NavigationTarget> pointer(int x, int y, boolean click, int scroll) {
        if (!open) {
            return Optional.empty();
        }
        if (click && !panel.contains(x, y)) {
            close();
            return Optional.empty();
        }
        if (!panel.contains(x, y)) {
            return Optional.empty();
        }
        if (scroll != 0) {
            move(scroll);
            return Optional.empty();
        }
        if (!click || !inner.contains(x, y)) {
            return Optional.empty();
        }
        int row = y - inner.y() - view().headerRows();
        int index = firstVisible + row;
        if (row < 0 || row >= visibleRows || index >= rows.size()) {
            return Optional.empty();
        }
        selected = index;
        return confirm();
    }

    /** Records the placed panel and its inner area, for hit testing and for the number of rows that fit. */
    public synchronized void layout(OverlayRegion placed, OverlayRegion innerArea) {
        panel = placed;
        inner = innerArea;
        visibleRows = Math.max(1, innerArea.height() - (filter.isEmpty() ? 0 : 1));
        keepSelectionVisible();
    }

    private Optional<NavigationTarget> confirm() {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        NavigationTarget target = rows.get(selected);
        close();
        return Optional.of(target);
    }

    private void type(int codePoint) {
        if (Character.isValidCodePoint(codePoint) && !Character.isISOControl(codePoint)) {
            filter += Character.toString(codePoint);
            refilter();
        }
    }

    private void backspace() {
        if (!filter.isEmpty()) {
            filter = filter.substring(0, filter.offsetByCodePoints(filter.length(), -1));
            refilter();
        }
    }

    private void refilter() {
        String needle = filter.toLowerCase(Locale.ROOT);
        rows = needle.isEmpty() ? all : all.stream().filter(target -> matches(target, needle)).toList();
        selected = 0;
        firstVisible = 0;
    }

    private static boolean matches(NavigationTarget target, String needle) {
        return (target.name() + " " + target.directory() + " " + target.position() + " " + target.preview())
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    private void move(int step) {
        if (rows.isEmpty()) {
            return;
        }
        selected = Math.clamp(selected + step, 0, rows.size() - 1);
        keepSelectionVisible();
    }

    private void keepSelectionVisible() {
        if (selected < firstVisible) {
            firstVisible = selected;
        } else if (selected >= firstVisible + visibleRows) {
            firstVisible = selected - visibleRows + 1;
        }
        firstVisible = Math.clamp(firstVisible, 0, Math.max(0, rows.size() - visibleRows));
    }
}
