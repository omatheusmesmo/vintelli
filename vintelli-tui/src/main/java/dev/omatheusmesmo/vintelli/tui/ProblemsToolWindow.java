package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Problems browser backed by the selected diagnostics capability. */
@Singleton
public final class ProblemsToolWindow implements ToolWindow {

    private static final String PROBLEMS_GLYPH = "";
    private static final String REFRESH_HINT = "R:Refresh";
    private static final Function<String, Style> NO_STYLE = role -> Style.EMPTY;

    private enum Filter { ALL, ERRORS, WARNINGS }
    private enum State { IDLE, LOADING, READY, EMPTY, UNAVAILABLE, ERROR }

    /**
     * The row the keyboard and the pointer act on: the header of a file group when {@code group} is set, otherwise
     * the problem at index {@code problem} of the problems list.
     */
    private record Selection(String group, int problem) {
        static Selection ofProblem(int index) {
            return new Selection(null, index);
        }

        static Selection ofGroup(String file) {
            return new Selection(file, -1);
        }
    }

    private record Group(String file, List<Integer> problems) {
    }

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Provider<ShellThemes> themes;
    private final IconMode iconMode;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("problems-refresh-", 0).factory());
    private final AtomicLong refreshSequence = new AtomicLong();
    private final List<Diagnostic> problems = new ArrayList<>();
    private Consumer<Diagnostic> navigateHandler;
    private State state = State.IDLE;
    private Filter filter = Filter.ALL;
    private String message = "Open one or more files in the editor, then refresh";
    private CapabilityStateLine.Summary capabilitySummary;
    private final boolean ideaConfigured;
    private final LongSupplier nanos;
    private final DoubleClickTracker clicks = new DoubleClickTracker();
    private final Set<String> collapsedGroups = new HashSet<>();
    private Selection selection = Selection.ofProblem(0);
    private int scrollOffset;
    private Rect lastArea = new Rect(0, 0, 0, 0);

    public ProblemsToolWindow(CapabilityRegistry registry, BuiltinEditor editor, Provider<ShellThemes> themes) {
        this(registry, editor, themes, Optional.empty(), IconMode.ASCII);
    }

    @Inject
    public ProblemsToolWindow(CapabilityRegistry registry, BuiltinEditor editor, Provider<ShellThemes> themes,
            @ConfigProperty(name = "vintelli.idea.mcp.url") Optional<String> ideaUrl) {
        this(registry, editor, themes, ideaUrl, FileIcons.resolve().mode());
    }

    ProblemsToolWindow(CapabilityRegistry registry, BuiltinEditor editor, Provider<ShellThemes> themes,
            Optional<String> ideaUrl, IconMode iconMode) {
        this(registry, editor, themes, ideaUrl, iconMode, System::nanoTime);
    }

    /** Takes the clock the double click is timed with, so a test does not depend on how fast the frames render. */
    ProblemsToolWindow(CapabilityRegistry registry, BuiltinEditor editor, Provider<ShellThemes> themes,
            Optional<String> ideaUrl, IconMode iconMode, LongSupplier nanos) {
        this.registry = registry;
        this.editor = editor;
        this.themes = themes;
        this.iconMode = iconMode;
        this.nanos = nanos;
        this.ideaConfigured = CapabilityStateLine.ideaConfigured(ideaUrl);
        this.capabilitySummary = registry == null
                ? CapabilityStateLine.notConfigured(CapabilityType.DIAGNOSTICS)
                : CapabilityStateLine.checking(CapabilityType.DIAGNOSTICS);
    }

    public ProblemsToolWindow(Provider<ShellThemes> themes) {
        this(null, null, themes);
    }

    public ProblemsToolWindow(ShellThemes themes) {
        this(null, null, () -> themes);
    }

    @Override public String id() { return "problems"; }
    @Override public String title() { return "Problems"; }
    @Override public char shortcut() { return '6'; }
    @Override public int ordinal() { return 2; }
    @Override public String icon() { return PROBLEMS_GLYPH; }

    @Override
    public boolean handleKey(KeyEvent event) {
        String action = actionForKey(event);
        return action != null && performAction(action, event);
    }

    @Override
    public String actionForKey(KeyEvent event) {
        if (event.isKey(KeyCode.UP)) return KeyActions.PROBLEMS_SELECT_PREVIOUS;
        if (event.isKey(KeyCode.DOWN)) return KeyActions.PROBLEMS_SELECT_NEXT;
        if (event.isKey(KeyCode.RIGHT)) return KeyActions.PROBLEMS_EXPAND;
        if (event.isKey(KeyCode.LEFT)) return KeyActions.PROBLEMS_COLLAPSE;
        if (event.isKey(KeyCode.ENTER)) {
            return headerSelected() ? KeyActions.PROBLEMS_TOGGLE : KeyActions.PROBLEMS_NAVIGATE;
        }
        if (event.code() != KeyCode.CHAR || event.hasCtrl() || event.hasAlt()) return null;
        return switch (Character.toLowerCase(event.codePoint())) {
            case 'r' -> KeyActions.PROBLEMS_REFRESH;
            case 'a' -> KeyActions.PROBLEMS_FILTER_ALL;
            case 'e' -> KeyActions.PROBLEMS_FILTER_ERRORS;
            case 'w' -> KeyActions.PROBLEMS_FILTER_WARNINGS;
            default -> null;
        };
    }

    /**
     * A click on a group header selects it and toggles it; a click on a problem selects it, and a second click on the
     * same problem within the double click window opens it, as Enter does. A click on a filter label applies that
     * filter, as its key does.
     */
    @Override
    public synchronized String actionForMouse(MouseEvent event) {
        if (!event.isClick() || !event.isLeftButton()) return null;
        if (inHeader(event.x(), event.y())) return filterActionAt(event.x(), event.y()).orElse(null);
        if (!insideContent(event.x(), event.y())) return null;
        Optional<ProblemRows.Row> hit = rowAt(event.y());
        if (hit.isEmpty()) return null;
        ProblemRows.Row row = hit.get();
        if (row.group() != null) {
            clicks.reset();
            selection = Selection.ofGroup(row.group());
            return KeyActions.PROBLEMS_TOGGLE;
        }
        if (row.problemIndex() < 0) return null;
        if (!clicks.register(row.problemIndex(), nanos.getAsLong())) return null;
        selection = Selection.ofProblem(row.problemIndex());
        return KeyActions.PROBLEMS_NAVIGATE;
    }

    @Override
    public synchronized boolean performAction(String actionId) {
        return switch (actionId) {
            case KeyActions.PROBLEMS_NAVIGATE -> navigateSelected();
            case KeyActions.PROBLEMS_REFRESH -> { refresh(); yield true; }
            case KeyActions.PROBLEMS_SELECT_PREVIOUS -> { moveSelection(-1); yield true; }
            case KeyActions.PROBLEMS_SELECT_NEXT -> { moveSelection(1); yield true; }
            case KeyActions.PROBLEMS_FILTER_ALL -> { setFilter(Filter.ALL); yield true; }
            case KeyActions.PROBLEMS_FILTER_ERRORS -> { setFilter(Filter.ERRORS); yield true; }
            case KeyActions.PROBLEMS_FILTER_WARNINGS -> { setFilter(Filter.WARNINGS); yield true; }
            case KeyActions.PROBLEMS_EXPAND -> expandSelectedGroup();
            case KeyActions.PROBLEMS_COLLAPSE -> collapseSelected();
            case KeyActions.PROBLEMS_TOGGLE -> toggleSelectedGroup();
            default -> false;
        };
    }

    @Override
    public boolean performAction(String actionId, KeyEvent event) {
        return performAction(actionId);
    }

    /** A single click selects the problem under the pointer. {@link #actionForMouse} opens it on the second click. */
    @Override
    public synchronized boolean handleMouse(MouseEvent event) {
        if (event.isScroll()) {
            if (event.kind() != MouseEventKind.SCROLL_UP && event.kind() != MouseEventKind.SCROLL_DOWN) return false;
            if (!inside(event.x(), event.y())) return false;
            int direction = event.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            scrollOffset = clamp(visibleScroll(lastArea.width(), lastArea.height()) + direction * 3, 0,
                    maxScroll(lastArea.width(), lastArea.height()));
            return true;
        }
        if (!event.isClick() || !event.isLeftButton() || !insideContent(event.x(), event.y())) return false;
        Optional<ProblemRows.Row> hit = rowAt(event.y());
        if (hit.isEmpty() || hit.get().group() != null || hit.get().problemIndex() < 0) return false;
        selection = Selection.ofProblem(hit.get().problemIndex());
        return true;
    }

    /**
     * Draws the header and the rows. Rendering only reads the state: the row layout is rebuilt from the problems for
     * the pane width, and the geometry of the last frame is kept for pointer hit-testing.
     */
    @Override
    public void render(Frame frame, Rect area) {
        List<Line> lines = new ArrayList<>();
        Style style;
        synchronized (this) {
            lastArea = area;
            if (state == State.IDLE) {
                worker.execute(this::refresh);
            }
            Function<String, Style> roles = roles();
            style = roles.apply("tool-window");
            List<String> header = header(area.width());
            header.forEach(text -> lines.add(Line.from(Span.styled(text, roles.apply("tool-window")))));
            List<ProblemRows.Row> rows = buildRows(area.width(), roles);
            int viewport = Math.max(1, area.height() - header.size());
            int scroll = clamp(scrollOffset, 0, maxScroll(area.width(), area.height()));
            rows.stream().skip(scroll).limit(viewport).forEach(row -> lines.add(row.line()));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).style(style).build(), area);
    }

    public void setNavigateHandler(Consumer<Diagnostic> handler) {
        synchronized (this) { navigateHandler = handler; }
    }

    public synchronized void refresh() {
        long request = refreshSequence.incrementAndGet();
        if (registry == null || editor == null) {
            state = State.UNAVAILABLE;
            message = "Diagnostics provider is not configured";
            return;
        }
        state = State.LOADING;
        message = "Refreshing diagnostics…";
        problems.clear();
        selection = Selection.ofProblem(0);
        scrollOffset = 0;
        worker.execute(() -> load(request));
    }

    public synchronized List<Diagnostic> problems() {
        return List.copyOf(problems);
    }

    /** The index of the selected problem, or -1 when a file header is selected. */
    public synchronized int selectedIndex() { return selection.problem(); }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }

    private void load(long request) {
        CapabilityStateLine.Summary summary = CapabilityStateLine.read(registry, CapabilityType.DIAGNOSTICS,
                ideaConfigured);
        synchronized (this) {
            if (request == refreshSequence.get()) {
                capabilitySummary = summary;
            }
        }
        try {
            loadDiagnostics(request);
        } catch (RuntimeException failure) {
            finishError(request, safeMessage(failure));
        }
    }

    private void loadDiagnostics(long request) {
        if (request != refreshSequence.get()) return;
        CapabilitySelection<DiagnosticsCapability> chosen = registry.diagnostics();
        if (chosen instanceof CapabilitySelection.Unavailable<DiagnosticsCapability> unavailable) {
            finishUnavailable(request, unavailable.reason());
            return;
        }
        DiagnosticsCapability provider = ((CapabilitySelection.Selected<DiagnosticsCapability>) chosen).provider();
        List<BuiltinEditor.TabView> tabs = editor.tabs();
        if (tabs.isEmpty()) {
            synchronized (this) {
                if (request != refreshSequence.get()) return;
                state = State.EMPTY;
                message = "Open files in the editor to inspect their diagnostics";
            }
            return;
        }

        List<Diagnostic> collected = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Path root = editor.project();
        for (BuiltinEditor.TabView tab : tabs) {
            if (request != refreshSequence.get()) return;
            Path file = root.resolve(tab.file()).normalize();
            try {
                CapabilityResult<List<Diagnostic>> result = provider.diagnostics(file);
                if (result instanceof CapabilityResult.Ok<List<Diagnostic>> ok) {
                    collected.addAll(ok.value());
                } else {
                    result.failureOf().ifPresent(failure -> failures.add(tab.file() + ": " + failure.reason()
                            + (failure.detail().isBlank() ? "" : ": " + failure.detail())));
                }
            } catch (RuntimeException failure) {
                failures.add(tab.file() + ": " + safeMessage(failure));
            }
        }
        collected.sort(Comparator.comparing((Diagnostic problem) -> relativePath(root, problem.filePath()))
                .thenComparingInt(Diagnostic::startLine).thenComparing(problem -> problem.severity().ordinal()));
        synchronized (this) {
            if (request != refreshSequence.get()) return;
            problems.clear();
            problems.addAll(collected);
            selection = defaultSelection();
            scrollOffset = 0;
            if (!collected.isEmpty()) {
                state = State.READY;
                message = failures.isEmpty() ? "" : "Some files could not be checked: " + failures.getFirst();
            } else if (!failures.isEmpty()) {
                state = State.ERROR;
                message = failures.getFirst();
            } else {
                state = State.EMPTY;
                message = "No problems in open files";
            }
        }
    }

    private synchronized void finishUnavailable(long request, String reason) {
        if (request != refreshSequence.get()) return;
        state = State.UNAVAILABLE;
        message = ProblemsStatusText.unavailable(reason, ideaConfigured);
    }

    private synchronized void finishError(long request, String reason) {
        if (request != refreshSequence.get()) return;
        state = State.ERROR;
        message = "Diagnostics refresh failed: " + reason;
    }

    private List<ProblemRows.Row> buildRows(int width, Function<String, Style> roles) {
        List<ProblemRows.Row> rows = new ArrayList<>();
        for (String skipped : capabilitySummary.skipped()) {
            rows.add(new ProblemRows.Row(Line.from(Span.styled(skipped, roles.apply("problem-skipped"))), -1));
        }
        if (state == State.LOADING || state == State.UNAVAILABLE || state == State.ERROR || state == State.IDLE) {
            rows.addAll(ProblemRows.plain(message, width, roles.apply("tool-window")));
            return rows;
        }
        List<Integer> visible = visibleProblems();
        if (visible.isEmpty()) {
            String empty = state == State.EMPTY ? message : "No problems match this filter";
            rows.addAll(ProblemRows.plain(empty, width, roles.apply("tool-window")));
            return rows;
        }
        if (!message.isBlank()) {
            rows.addAll(ProblemRows.plain("! " + message, width, roles.apply("problem-warning")));
        }
        for (Group group : visibleGroups()) {
            boolean expanded = !collapsedGroups.contains(group.file());
            rows.addAll(ProblemRows.group(group.file(), group.problems().size(), expanded,
                    group.file().equals(selection.group()), width, iconMode, roles));
            if (!expanded) continue;
            for (int index : group.problems()) {
                rows.addAll(ProblemRows.problem(index, problems.get(index), index == selection.problem(), width,
                        iconMode, roles));
            }
        }
        return rows;
    }

    private List<Group> visibleGroups() {
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (int index : visibleProblems()) {
            grouped.computeIfAbsent(groupOf(index), file -> new ArrayList<>()).add(index);
        }
        return grouped.entrySet().stream().map(entry -> new Group(entry.getKey(), entry.getValue())).toList();
    }

    /** The rows keyboard navigation can reach, in order: each visible header, then its problems while it is open. */
    private List<Selection> visibleEntries() {
        List<Selection> entries = new ArrayList<>();
        for (Group group : visibleGroups()) {
            entries.add(Selection.ofGroup(group.file()));
            if (!collapsedGroups.contains(group.file())) {
                group.problems().forEach(index -> entries.add(Selection.ofProblem(index)));
            }
        }
        return entries;
    }

    private String groupOf(int index) {
        return relativePath(editor.project(), problems.get(index).filePath());
    }

    private List<String> header(int width) {
        String headline = state == State.UNAVAILABLE
                ? CapabilityStateLine.label(CapabilityType.DIAGNOSTICS)
                : capabilitySummary.headline();
        String status = headline + "  " + stateLabel();
        if (state == State.READY || state == State.EMPTY) {
            status += "  " + countsLabel();
        }
        String actions = state == State.UNAVAILABLE ? REFRESH_HINT : filterLabel() + "  " + REFRESH_HINT;
        String single = status + "  " + actions;
        if (CharWidth.of(single) <= width) {
            return List.of(single);
        }
        List<String> lines = new ArrayList<>(ProblemRows.wrap(status, width, width));
        lines.addAll(ProblemRows.wrap(actions, width, width));
        return lines;
    }

    private String countsLabel() {
        int errors = 0;
        int warnings = 0;
        int others = 0;
        for (Diagnostic problem : problems) {
            switch (problem.severity()) {
                case ERROR -> errors++;
                case WARNING -> warnings++;
                case INFORMATION, HINT -> others++;
            }
        }
        return ProblemsStatusText.counts(errors, warnings, others);
    }

    private List<Integer> visibleProblems() {
        List<Integer> visible = new ArrayList<>();
        for (int index = 0; index < problems.size(); index++) {
            Severity severity = problems.get(index).severity();
            if (filter == Filter.ERRORS && severity != Severity.ERROR) continue;
            if (filter == Filter.WARNINGS && severity != Severity.WARNING) continue;
            visible.add(index);
        }
        return visible;
    }

    /** The first visible problem, or its file header when that problem sits in a collapsed group. */
    private Selection defaultSelection() {
        List<Integer> visible = visibleProblems();
        if (visible.isEmpty()) return Selection.ofProblem(0);
        int first = visible.getFirst();
        String group = groupOf(first);
        return collapsedGroups.contains(group) ? Selection.ofGroup(group) : Selection.ofProblem(first);
    }

    private void moveSelection(int delta) {
        List<Selection> entries = visibleEntries();
        if (entries.isEmpty()) return;
        int current = Math.max(0, entries.indexOf(selection));
        selection = entries.get(clamp(current + delta, 0, entries.size() - 1));
        revealSelection();
    }

    private boolean navigateSelected() {
        int index = selection.problem();
        if (index < 0 || index >= problems.size()) return false;
        Consumer<Diagnostic> handler = navigateHandler;
        if (handler == null) return false;
        handler.accept(problems.get(index));
        return true;
    }

    private void setFilter(Filter next) {
        filter = next;
        scrollOffset = 0;
        if (!visibleEntries().contains(selection)) {
            selection = defaultSelection();
        }
    }

    private boolean expandSelectedGroup() {
        String group = selection.group();
        if (group == null || !collapsedGroups.remove(group)) return false;
        revealSelection();
        return true;
    }

    /**
     * Collapses the group of the selected header, or of the selected problem, which moves the selection to that
     * header so that it never rests on a row the collapse hides.
     */
    private boolean collapseSelected() {
        if (selection.group() == null) {
            if (selection.problem() < 0 || selection.problem() >= problems.size()) return false;
            selection = Selection.ofGroup(groupOf(selection.problem()));
        }
        if (!collapsedGroups.add(selection.group())) return false;
        revealSelection();
        return true;
    }

    private boolean toggleSelectedGroup() {
        String group = selection.group();
        if (group == null) return false;
        if (!collapsedGroups.remove(group)) {
            collapsedGroups.add(group);
        }
        revealSelection();
        return true;
    }

    /** Moves the viewport the least needed to show the selected row. */
    private void revealSelection() {
        int row = rowForSelection(buildRows(lastArea.width(), NO_STYLE));
        int viewport = Math.max(1, lastArea.height() - header(lastArea.width()).size());
        int scroll = visibleScroll(lastArea.width(), lastArea.height());
        if (row < scroll) {
            scrollOffset = row;
        } else if (row >= scroll + viewport) {
            scrollOffset = row - viewport + 1;
        } else {
            scrollOffset = scroll;
        }
    }

    private int rowForSelection(List<ProblemRows.Row> rows) {
        for (int row = 0; row < rows.size(); row++) {
            ProblemRows.Row candidate = rows.get(row);
            boolean sameGroup = Objects.equals(candidate.group(), selection.group());
            if (sameGroup && candidate.problemIndex() == selection.problem()) {
                return row;
            }
        }
        return 0;
    }

    private Optional<ProblemRows.Row> rowAt(int y) {
        int rowIndex = visibleScroll(lastArea.width(), lastArea.height()) + y - contentTop();
        List<ProblemRows.Row> rows = buildRows(lastArea.width(), NO_STYLE);
        return rowIndex >= 0 && rowIndex < rows.size() ? Optional.of(rows.get(rowIndex)) : Optional.empty();
    }

    private boolean headerSelected() {
        return selection.group() != null;
    }

    private Optional<String> filterActionAt(int x, int y) {
        List<String> header = header(lastArea.width());
        int line = y - lastArea.y();
        if (line < 0 || line >= header.size()) return Optional.empty();
        String text = header.get(line);
        int column = x - lastArea.x();
        for (Filter option : Filter.values()) {
            String label = filterToken(option);
            int start = text.indexOf(label);
            if (start < 0) continue;
            int from = CharWidth.of(text.substring(0, start));
            if (column >= from && column < from + CharWidth.of(label)) {
                return Optional.of(filterAction(option));
            }
        }
        return Optional.empty();
    }

    private static String filterAction(Filter option) {
        return switch (option) {
            case ALL -> KeyActions.PROBLEMS_FILTER_ALL;
            case ERRORS -> KeyActions.PROBLEMS_FILTER_ERRORS;
            case WARNINGS -> KeyActions.PROBLEMS_FILTER_WARNINGS;
        };
    }

    private int visibleScroll(int width, int height) {
        return clamp(scrollOffset, 0, maxScroll(width, height));
    }

    private int maxScroll(int width, int height) {
        int viewport = Math.max(1, height - header(width).size());
        return Math.max(0, buildRows(width, NO_STYLE).size() - viewport);
    }

    private int contentTop() {
        return lastArea.y() + header(lastArea.width()).size();
    }

    private boolean inside(int x, int y) {
        return x >= lastArea.x() && x < lastArea.x() + lastArea.width()
                && y > lastArea.y() && y < lastArea.y() + lastArea.height();
    }

    private boolean insideContent(int x, int y) {
        return x >= lastArea.x() && x < lastArea.x() + lastArea.width()
                && y >= contentTop() && y < lastArea.y() + lastArea.height();
    }

    private boolean inHeader(int x, int y) {
        return x >= lastArea.x() && x < lastArea.x() + lastArea.width()
                && y >= lastArea.y() && y < contentTop();
    }

    private Function<String, Style> roles() {
        ShellThemes active = themes == null ? null : themes.get();
        return role -> active == null ? Style.EMPTY : active.resolver(role).toStyle();
    }

    private String filterLabel() {
        return filterToken(Filter.ALL) + "  " + filterToken(Filter.ERRORS) + "  " + filterToken(Filter.WARNINGS);
    }

    /** The label of one filter as the header draws it: bracketed when it is the active filter. */
    private String filterToken(Filter option) {
        return switch (option) {
            case ALL -> filter == Filter.ALL ? "[A]ll" : "A:All";
            case ERRORS -> filter == Filter.ERRORS ? "[E]rr" : "E:Err";
            case WARNINGS -> filter == Filter.WARNINGS ? "[W]arn" : "W:Warn";
        };
    }
    private String stateLabel() { return switch (state) { case IDLE -> "Idle"; case LOADING -> "Loading…"; case READY -> "Ready"; case EMPTY -> "Empty"; case UNAVAILABLE -> "Unavailable"; case ERROR -> "Error"; }; }
    private static String relativePath(Path root, Path file) {
        Path normalized = file.isAbsolute() ? file.normalize() : root.resolve(file).normalize();
        return normalized.startsWith(root.normalize()) ? root.normalize().relativize(normalized).toString().replace('\\', '/') : normalized.toString().replace('\\', '/');
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String safeMessage(RuntimeException failure) { return failure.getMessage() == null ? failure.toString() : failure.getMessage(); }
}
