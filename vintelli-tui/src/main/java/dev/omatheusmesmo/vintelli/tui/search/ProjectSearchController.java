package dev.omatheusmesmo.vintelli.tui.search;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.FileMatch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.TextMatch;
import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * State and rules of the project search overlay on top of {@link ProjectSearchModel}:
 * the panel and the rows the last render showed, the scroll window and the pointer
 * hits. It holds no TamboUI type; the overlay draws the placement it returns and
 * forwards the events.
 */
final class ProjectSearchController {

    /** A confirmed result: the file, and the zero-based line for text matches. */
    record Target(Path file, int line) {
    }

    /** The panel the render pass placed, with the window of rows it shows. */
    record Placement(OverlayRegion panel, OverlayRegion inner, ProjectSearchModel.View view, int visibleRows,
            int first) {
    }

    private static final int MIN_WIDTH = 8;
    private static final int MIN_HEIGHT = 4;
    private static final int MAX_WIDTH = 76;
    private static final int MIN_PANEL_HEIGHT = 5;
    private static final int MAX_PANEL_HEIGHT = 20;
    private static final int WHEEL_STEP = 3;

    private final ProjectSearchModel model;
    private final DoubleClickTracker doubleClick = new DoubleClickTracker();
    private OverlayRegion panel = OverlayRegion.EMPTY;
    private OverlayRegion inner = OverlayRegion.EMPTY;
    private ProjectSearchModel.View renderedView;
    private int renderedFirst;
    private int renderedRows;
    private int scrollOffset;

    ProjectSearchController(ProjectSearchModel model) {
        this.model = model;
    }

    /** Returns the confirmed target, or empty when the key was consumed without confirmation. */
    Optional<Target> press(OverlayKey key, int codePoint) {
        if (!model.isOpen()) {
            return Optional.empty();
        }
        switch (key) {
            case ESCAPE -> model.close();
            case ENTER -> {
                FileMatch file = model.selectedFile();
                if (file != null) {
                    model.close();
                    return Optional.of(new Target(file.file(), 0));
                }
                TextMatch match = model.selectedText();
                if (match != null) {
                    model.close();
                    return Optional.of(new Target(match.file(), match.line()));
                }
            }
            case UP -> model.move(-1);
            case DOWN -> model.move(1);
            case BACKSPACE -> model.backspace();
            case TEXT -> model.type(codePoint);
            default -> {
            }
        }
        return Optional.empty();
    }

    /**
     * Places the panel in {@code area} and records it with the rows it shows. An
     * empty result means the overlay is closed or the area is too small, and the
     * hit box is cleared.
     */
    Optional<Placement> place(OverlayRegion area) {
        if (!model.isOpen() || area.width() < MIN_WIDTH || area.height() < MIN_HEIGHT) {
            panel = OverlayRegion.EMPTY;
            renderedView = null;
            scrollOffset = 0;
            return Optional.empty();
        }
        ProjectSearchModel.View view = model.view();
        if (renderedView == null || renderedView.mode() != view.mode()
                || !renderedView.query().equals(view.query())) {
            scrollOffset = 0;
        }
        int width = Math.min(MAX_WIDTH, area.width() - 2);
        int size = size(view);
        int rows = size + (view.error() != null && size > 0 ? 1 : 0);
        int height = Math.min(area.height() - 2,
                Math.max(MIN_PANEL_HEIGHT, Math.min(rows + 3, MAX_PANEL_HEIGHT)));
        OverlayRegion placed = new OverlayRegion(area.x() + (area.width() - width) / 2,
                area.y() + (area.height() - height) / 2, width, height);
        OverlayRegion innerRegion = placed.inset(1);
        panel = placed;
        inner = innerRegion;
        renderedView = view;
        int visibleRows = Math.max(0, innerRegion.height() - 1);
        int first = Math.clamp(scrollOffset, 0, Math.max(0, size - visibleRows));
        if (view.selected() < first) {
            first = view.selected();
        } else if (visibleRows > 0 && view.selected() >= first + visibleRows) {
            first = view.selected() - visibleRows + 1;
        }
        scrollOffset = first;
        renderedFirst = first;
        renderedRows = Math.min(visibleRows, Math.max(0, size - first));
        return Optional.of(new Placement(placed, innerRegion, view, visibleRows, first));
    }

    /**
     * Handles pointer input: a click outside the panel closes it, a wheel scrolls
     * the rows, and a second click on the same row within the double click window
     * confirms it. A scroll is -1 for a wheel up, 1 for a wheel down and 0 otherwise.
     */
    Optional<Target> pointer(int x, int y, boolean click, int scroll, long nanos) {
        if (!model.isOpen()) {
            return Optional.empty();
        }
        if (click && !panel.contains(x, y)) {
            model.close();
            renderedView = null;
            return Optional.empty();
        }
        ProjectSearchModel.View current = model.view();
        if (renderedView == null || !sameResults(renderedView, current) || !panel.contains(x, y)) {
            return Optional.empty();
        }
        if (scroll != 0) {
            wheel(x, y, scroll, current);
            return Optional.empty();
        }
        if (!click || !inner.contains(x, y) || y < inner.y() + 1) {
            if (click) {
                doubleClick.reset();
            }
            return Optional.empty();
        }
        int row = y - inner.y() - 1;
        if (row < 0 || row >= renderedRows) {
            doubleClick.reset();
            return Optional.empty();
        }
        int index = renderedFirst + row;
        model.select(index);
        if (!doubleClick.register(index, nanos)) {
            return Optional.empty();
        }
        Target target = current.mode() == ProjectSearchModel.Mode.FILES
                ? new Target(current.files().get(index).file(), 0)
                : new Target(current.text().get(index).file(), current.text().get(index).line());
        model.close();
        renderedView = null;
        return Optional.of(target);
    }

    private void wheel(int x, int y, int direction, ProjectSearchModel.View current) {
        doubleClick.reset();
        if (!inner.contains(x, y) || y < inner.y() + 1 || renderedRows == 0) {
            return;
        }
        int visibleRows = Math.max(1, inner.height() - 1);
        int size = size(current);
        scrollOffset = Math.clamp(scrollOffset + direction * WHEEL_STEP, 0, Math.max(0, size - visibleRows));
        model.select(Math.clamp(current.selected(), scrollOffset,
                Math.min(size - 1, scrollOffset + visibleRows - 1)));
    }

    private static int size(ProjectSearchModel.View view) {
        return view.mode() == ProjectSearchModel.Mode.FILES ? view.files().size() : view.text().size();
    }

    private static boolean sameResults(ProjectSearchModel.View rendered, ProjectSearchModel.View current) {
        return rendered.mode() == current.mode() && rendered.query().equals(current.query())
                && rendered.regex() == current.regex() && rendered.files().equals(current.files())
                && rendered.text().equals(current.text()) && rendered.searching() == current.searching()
                && Objects.equals(rendered.error(), current.error());
    }
}
