package dev.omatheusmesmo.vintelli.tui.maven;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

import dev.omatheusmesmo.vintelli.api.maven.MavenLifecycle;
import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;
import dev.omatheusmesmo.vintelli.tui.Marquee;

/**
 * Tree state of the Maven window: which module nodes are expanded, which row
 * is selected, where the viewport starts and which phase a run activates. It
 * holds no TamboUI type, so the keyboard and pointer paths share these rules
 * and the rules are tested without a terminal.
 */
public final class MavenProjectController {

    public enum RowKind {
        MODULE,
        LIFECYCLE,
        PHASE,
        PROFILES,
        PROFILE,
        PLUGINS,
        PLUGIN,
        GOAL
    }

    /**
     * One visible row: a module node, a lifecycle phase, the Profiles group or a profile checkbox, owned by the
     * module at {@code selector}. {@code checked} is only meaningful for a profile row.
     */
    public record Row(RowKind kind, String selector, int depth, String label, String phase, boolean expanded,
            boolean checked) {
    }

    private static final String ROOT = "";

    private MavenProjectLoad load;
    private final Set<String> expanded = new HashSet<>(Set.of(ROOT));
    private final Map<String, Set<String>> selectedProfiles = new HashMap<>();
    private final DoubleClickTracker clicks = new DoubleClickTracker();
    private final Marquee marquee;
    private int selected;
    private int scrollOffset;
    private int viewport;
    private boolean skipTests;

    public MavenProjectController() {
        this(System::nanoTime);
    }

    public MavenProjectController(LongSupplier clock) {
        this.marquee = new Marquee(clock);
    }

    public synchronized void useMarquee(boolean enabled) {
        marquee.enable(enabled);
    }

    public synchronized void onMarqueeChange(Runnable listener) {
        marquee.onChange(listener);
    }

    public synchronized boolean marqueeMoving() {
        return marquee.moving();
    }

    public synchronized int marqueeOffset() {
        return marquee.offset();
    }

    /** Advances the marquee of the selected row or of the status line by one tick; the shell calls it, never a render. */
    public synchronized void tickMarquee() {
        marquee.follow(selectionKey());
        marquee.tick();
    }

    /**
     * Records the width of the selected row, or of the status line, against the
     * columns the line can show after its fixed prefix. The name scrolls only when
     * the prefix leaves room for it.
     */
    public synchronized void fitSelected(int width, int prefixWidth, int labelWidth) {
        marquee.fit(width - prefixWidth > 0 ? prefixWidth + labelWidth - width : 0);
    }

    /** The full text of the line the marquee tracks: the status message, or else the selected row. */
    public synchronized Optional<String> selectionText() {
        return statusMessage().or(() -> selectedRow().map(Row::label));
    }

    private Object selectionKey() {
        Optional<String> status = statusMessage();
        return status.isPresent() ? status.get() : selectedRow().orElse(null);
    }

    public synchronized void startLoading() {
        load = null;
        reset();
    }

    public synchronized void finishLoading(MavenProjectLoad result) {
        load = result;
        if (result instanceof MavenProjectLoad.Loaded loaded) {
            pruneProfiles(loaded.root());
        }
        reset();
    }

    private void pruneProfiles(MavenModule root) {
        Map<String, List<String>> declared = new HashMap<>();
        declareProfiles(root, declared);
        selectedProfiles.keySet().removeIf(selector -> !declared.containsKey(selector));
        selectedProfiles.forEach((selector, ids) -> ids.retainAll(declared.get(selector)));
        selectedProfiles.values().removeIf(Set::isEmpty);
    }

    private static void declareProfiles(MavenModule module, Map<String, List<String>> declared) {
        declared.put(module.selector(), module.profiles());
        for (MavenModule child : module.modules()) {
            declareProfiles(child, declared);
        }
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
        rows.add(new Row(RowKind.MODULE, module.selector(), depth, module.artifactId(), null, open, false));
        if (!open) {
            return;
        }
        rows.add(new Row(RowKind.LIFECYCLE, module.selector(), depth + 1, "Lifecycle", null, true, false));
        for (String phase : MavenLifecycle.PHASES) {
            rows.add(new Row(RowKind.PHASE, module.selector(), depth + 1, phase, phase, false, false));
        }
        if (!module.profiles().isEmpty()) {
            rows.add(new Row(RowKind.PROFILES, module.selector(), depth + 1, "Profiles", null, false, false));
            Set<String> selected = selectedProfiles.getOrDefault(module.selector(), Set.of());
            for (String id : module.profiles()) {
                rows.add(new Row(RowKind.PROFILE, module.selector(), depth + 2, id, null, false,
                        selected.contains(id)));
            }
        }
        if (!module.plugins().isEmpty()) {
            rows.add(new Row(RowKind.PLUGINS, module.selector(), depth + 1, "Plugins", null, true, false));
            for (MavenPlugin plugin : module.plugins()) {
                rows.add(new Row(RowKind.PLUGIN, module.selector(), depth + 2, plugin.displayName(), null, true,
                        false));
                for (String goal : plugin.goals()) {
                    String goalId = plugin.goalId(goal);
                    rows.add(new Row(RowKind.GOAL, module.selector(), depth + 3, goalId, goalId, false, false));
                }
            }
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
            if (index != selected) {
                marquee.restart();
            }
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
     * Runs the selected phase with the profiles selected for its module, toggles
     * the selected module node or profile, and does nothing on the Profiles group.
     * Only a phase row yields a goal.
     */
    public synchronized Optional<MavenGoal> activateSelected() {
        Optional<Row> row = selectedRow();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row current = row.get();
        switch (current.kind()) {
            case PHASE, GOAL -> {
                return Optional.of(new MavenGoal(current.phase(), current.selector(),
                        selectedProfileIds(current.selector()), skipTests));
            }
            case PROFILE -> toggleProfile(current.selector(), current.label());
            case LIFECYCLE, PROFILES, PLUGINS, PLUGIN -> {
            }
            case MODULE -> {
                if (current.expanded()) {
                    expanded.remove(current.selector());
                } else {
                    expanded.add(current.selector());
                }
                clampSelection();
            }
        }
        return Optional.empty();
    }

    public synchronized boolean skipTests() {
        return skipTests;
    }

    public synchronized boolean toggleSkipTests() {
        skipTests = !skipTests;
        return skipTests;
    }

    /** Toggles the profile under the selection when it is a profile row, and reports whether it was one. */
    public synchronized boolean isProfileRow(int index) {
        List<Row> rows = rows();
        return index >= 0 && index < rows.size() && rows.get(index).kind() == RowKind.PROFILE;
    }

    public synchronized boolean toggleSelectedProfile() {
        Optional<Row> row = selectedRow();
        if (row.isEmpty() || row.get().kind() != RowKind.PROFILE) {
            return false;
        }
        toggleProfile(row.get().selector(), row.get().label());
        return true;
    }

    private void toggleProfile(String selector, String id) {
        Set<String> selected = selectedProfiles.computeIfAbsent(selector, key -> new HashSet<>());
        if (!selected.remove(id)) {
            selected.add(id);
        }
        if (selected.isEmpty()) {
            selectedProfiles.remove(selector);
        }
    }

    /** The profile ids selected for one module, in the order the module declares them. */
    private List<String> selectedProfileIds(String selector) {
        Set<String> selected = selectedProfiles.getOrDefault(selector, Set.of());
        return findModule(selector).map(module -> module.profiles().stream()
                .filter(selected::contains)
                .toList()).orElse(List.of());
    }

    private Optional<MavenModule> findModule(String selector) {
        if (load instanceof MavenProjectLoad.Loaded loaded) {
            return findModule(loaded.root(), selector);
        }
        return Optional.empty();
    }

    private static Optional<MavenModule> findModule(MavenModule module, String selector) {
        if (module.selector().equals(selector)) {
            return Optional.of(module);
        }
        for (MavenModule child : module.modules()) {
            Optional<MavenModule> found = findModule(child, selector);
            if (found.isPresent()) {
                return found;
            }
        }
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
            Row owner = rows.get(candidate);
            boolean groupOwner = owner.kind() == RowKind.MODULE || owner.kind() == RowKind.PROFILES
                    || owner.kind() == RowKind.LIFECYCLE || owner.kind() == RowKind.PLUGINS
                    || owner.kind() == RowKind.PLUGIN;
            if (groupOwner && owner.depth() == depth - 1) {
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
