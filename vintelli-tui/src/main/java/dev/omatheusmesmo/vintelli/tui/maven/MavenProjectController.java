package dev.omatheusmesmo.vintelli.tui.maven;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.omatheusmesmo.vintelli.api.build.MavenLifecycle;
import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;

/**
 * Tree state of the Maven window: which module nodes are expanded, which row
 * is selected, where the viewport starts and which phase a run activates. It
 * holds no TamboUI type, so the keyboard and pointer paths share these rules
 * and the rules are tested without a terminal.
 */
public final class MavenProjectController {

    public enum RowKind {
        MODULE,
        PHASE
    }

    /** One visible row: a module node, or a lifecycle phase owned by the module at {@code selector}. */
    public record Row(RowKind kind, String selector, int depth, String label, String phase, boolean expanded) {
    }

    private static final String ROOT = "";

    private MavenProjectLoad load;
    private final Set<String> expanded = new HashSet<>(Set.of(ROOT));
    private final DoubleClickTracker clicks = new DoubleClickTracker();
    private int selected;
    private int scrollOffset;
    private int viewport;

    public synchronized void startLoading() {
        load = null;
        reset();
    }

    public synchronized void finishLoading(MavenProjectLoad result) {
        load = result;
        reset();
    }

    private void reset() {
        expanded.clear();
        expanded.add(ROOT);
        clicks.reset();
        selected = 0;
        scrollOffset = 0;
    }

    /** The reason the tree cannot show rows right now, or empty when it can. */
    public synchronized Optional<String> statusMessage() {
        if (load == null) {
            return Optional.of("Loading Maven project...");
        }
        if (load instanceof MavenProjectLoad.Missing missing) {
            return Optional.of("No Maven project: " + missing.reason());
        }
        if (load instanceof MavenProjectLoad.Unreadable unreadable) {
            return Optional.of("Cannot read the Maven project: " + unreadable.reason());
        }
        return Optional.empty();
    }

    public synchronized List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (load instanceof MavenProjectLoad.Loaded loaded) {
            collect(loaded.root(), 0, rows);
        }
        return rows;
    }

    private void collect(MavenModule module, int depth, List<Row> rows) {
        boolean open = expanded.contains(module.selector());
        rows.add(new Row(RowKind.MODULE, module.selector(), depth, module.artifactId(), null, open));
        if (!open) {
            return;
        }
        for (String phase : MavenLifecycle.PHASES) {
            rows.add(new Row(RowKind.PHASE, module.selector(), depth + 1, phase, phase, false));
        }
        for (MavenModule child : module.modules()) {
            collect(child, depth + 1, rows);
        }
    }

    public synchronized int selected() {
        return selected;
    }

    public synchronized int scrollOffset() {
        return scrollOffset;
    }

    public synchronized int viewport() {
        return viewport;
    }

    /** Records the rows the window can show; the scroll position follows the selection inside it. */
    public synchronized void fitViewport(int rows) {
        viewport = Math.max(0, rows);
        scrollOffset = clamp(scrollOffset, 0, maxScroll());
        keepSelectionVisible();
    }

    public synchronized void select(int index) {
        if (index >= 0 && index < rows().size()) {
            selected = index;
            keepSelectionVisible();
        }
    }

    public synchronized void selectNext() {
        select(Math.min(rows().size() - 1, selected + 1));
    }

    public synchronized void selectPrevious() {
        select(Math.max(0, selected - 1));
    }

    /** Expands the selected module node; phases have nothing to expand. */
    public synchronized void expandSelected() {
        Optional<Row> row = selectedRow();
        if (row.isPresent() && row.get().kind() == RowKind.MODULE) {
            expanded.add(row.get().selector());
        }
    }

    /** Collapses the selected module node, or moves to the owning module when the row is not an open module. */
    public synchronized void collapseSelected() {
        Optional<Row> row = selectedRow();
        if (row.isEmpty()) {
            return;
        }
        Row current = row.get();
        if (current.kind() == RowKind.MODULE && current.expanded()) {
            expanded.remove(current.selector());
            clampSelection();
            return;
        }
        int parent = parentIndex(selected);
        if (parent >= 0) {
            select(parent);
        }
    }

    /**
     * Runs the selected phase, or toggles the selected module node. A phase
     * row yields the goal; a module row yields nothing.
     */
    public synchronized Optional<MavenGoal> activateSelected() {
        Optional<Row> row = selectedRow();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row current = row.get();
        if (current.kind() == RowKind.PHASE) {
            return Optional.of(new MavenGoal(current.phase(), current.selector()));
        }
        if (current.expanded()) {
            expanded.remove(current.selector());
        } else {
            expanded.add(current.selector());
        }
        clampSelection();
        return Optional.empty();
    }

    /**
     * Records a press on a row and reports whether it completes a double
     * click on that row. Callers pass the monotonic time of the press.
     */
    public synchronized boolean registerClick(int index, long nanos) {
        return index >= 0 && index < rows().size() && clicks.register(index, nanos);
    }

    /**
     * Moves the viewport by a wheel step. The selection moves into the new
     * viewport too, so the next render does not snap the view back to it.
     */
    public synchronized void scrollBy(int delta) {
        scrollOffset = clamp(scrollOffset + delta, 0, maxScroll());
        if (viewport > 0) {
            int last = Math.min(rows().size() - 1, scrollOffset + viewport - 1);
            selected = clamp(selected, scrollOffset, Math.max(scrollOffset, last));
        }
    }

    /** The row index under a viewport row offset, or -1 when that offset has no row. */
    public synchronized int rowAt(int viewportOffset) {
        int index = scrollOffset + viewportOffset;
        return viewportOffset >= 0 && viewportOffset < viewport && index < rows().size() ? index : -1;
    }

    private Optional<Row> selectedRow() {
        List<Row> rows = rows();
        return selected >= 0 && selected < rows.size() ? Optional.of(rows.get(selected)) : Optional.empty();
    }

    private int parentIndex(int index) {
        List<Row> rows = rows();
        if (index < 0 || index >= rows.size()) {
            return -1;
        }
        int depth = rows.get(index).depth();
        for (int candidate = index - 1; candidate >= 0; candidate--) {
            if (rows.get(candidate).kind() == RowKind.MODULE && rows.get(candidate).depth() == depth - 1) {
                return candidate;
            }
        }
        return -1;
    }

    private void clampSelection() {
        selected = clamp(selected, 0, Math.max(0, rows().size() - 1));
        keepSelectionVisible();
    }

    private void keepSelectionVisible() {
        if (viewport <= 0) {
            return;
        }
        if (selected < scrollOffset) {
            scrollOffset = selected;
        } else if (selected >= scrollOffset + viewport) {
            scrollOffset = selected - viewport + 1;
        }
        scrollOffset = clamp(scrollOffset, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, rows().size() - viewport);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
