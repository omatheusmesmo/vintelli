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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** Problems browser backed by the selected diagnostics capability. */
@Singleton
public final class ProblemsToolWindow implements ToolWindow {

    private static final String PROBLEMS_GLYPH = "";
    private static final String REFRESH_HINT = "R:Refresh";
    private static final Function<String, Style> NO_STYLE = role -> Style.EMPTY;

    private enum Filter { ALL, ERRORS, WARNINGS }
    private enum State { IDLE, LOADING, READY, EMPTY, UNAVAILABLE, ERROR }

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
    private int selectedIndex;
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
        this.registry = registry;
        this.editor = editor;
        this.themes = themes;
        this.iconMode = iconMode;
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
        if (event.isKey(KeyCode.ENTER)) return KeyActions.PROBLEMS_NAVIGATE;
        if (event.code() != KeyCode.CHAR || event.hasCtrl() || event.hasAlt()) return null;
        return switch (Character.toLowerCase(event.codePoint())) {
            case 'r' -> KeyActions.PROBLEMS_REFRESH;
            case 'a' -> KeyActions.PROBLEMS_FILTER_ALL;
            case 'e' -> KeyActions.PROBLEMS_FILTER_ERRORS;
            case 'w' -> KeyActions.PROBLEMS_FILTER_WARNINGS;
            default -> null;
        };
    }

    @Override
    public synchronized String actionForMouse(MouseEvent event) {
        if (!event.isClick() || !event.isLeftButton() || !insideContent(event.x(), event.y())) return null;
        int rowIndex = visibleScroll(lastArea.width(), lastArea.height()) + event.y() - contentTop();
        List<ProblemRows.Row> rows = buildRows(lastArea.width(), NO_STYLE);
        if (rowIndex < 0 || rowIndex >= rows.size()) return null;
        int problemIndex = rows.get(rowIndex).problemIndex();
        if (problemIndex < 0) return null;
        selectedIndex = problemIndex;
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
            default -> false;
        };
    }

    @Override
    public boolean performAction(String actionId, KeyEvent event) {
        return performAction(actionId);
    }

    @Override
    public synchronized boolean handleMouse(MouseEvent event) {
        if (!event.isScroll()) return false;
        if (event.kind() != MouseEventKind.SCROLL_UP && event.kind() != MouseEventKind.SCROLL_DOWN) return false;
        if (!inside(event.x(), event.y())) return false;
        int direction = event.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
        scrollOffset = clamp(visibleScroll(lastArea.width(), lastArea.height()) + direction * 3, 0,
                maxScroll(lastArea.width(), lastArea.height()));
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
        selectedIndex = 0;
        scrollOffset = 0;
        worker.execute(() -> load(request));
    }

    public synchronized List<Diagnostic> problems() {
        return List.copyOf(problems);
    }

    public synchronized int selectedIndex() { return selectedIndex; }

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
        CapabilitySelection<DiagnosticsCapability> selection = registry.diagnostics();
        if (selection instanceof CapabilitySelection.Unavailable<DiagnosticsCapability> unavailable) {
            finishUnavailable(request, unavailable.reason());
            return;
        }
        DiagnosticsCapability provider = ((CapabilitySelection.Selected<DiagnosticsCapability>) selection).provider();
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
            selectedIndex = firstVisibleIndex();
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
        String currentFile = null;
        for (int index : visible) {
            Diagnostic diagnostic = problems.get(index);
            String file = relativePath(editor.project(), diagnostic.filePath());
            if (!file.equals(currentFile)) {
                currentFile = file;
                rows.addAll(ProblemRows.plain(file, width, roles.apply("tool-window-title")));
            }
            rows.addAll(ProblemRows.problem(index, diagnostic, index == selectedIndex, width, iconMode, roles));
        }
        return rows;
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

    private int firstVisibleIndex() {
        return visibleProblems().stream().findFirst().orElse(0);
    }

    private void moveSelection(int delta) {
        List<Integer> visible = visibleProblems();
        if (visible.isEmpty()) return;
        int current = Math.max(0, visible.indexOf(selectedIndex));
        selectedIndex = visible.get(clamp(current + delta, 0, visible.size() - 1));
        int row = rowForProblem(buildRows(lastArea.width(), NO_STYLE), selectedIndex);
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

    private boolean navigateSelected() {
        if (selectedIndex < 0 || selectedIndex >= problems.size()) return false;
        Consumer<Diagnostic> handler = navigateHandler;
        if (handler == null) return false;
        handler.accept(problems.get(selectedIndex));
        return true;
    }

    private void setFilter(Filter next) {
        filter = next;
        scrollOffset = 0;
        if (!visibleProblems().contains(selectedIndex)) {
            selectedIndex = firstVisibleIndex();
        }
    }

    private static int rowForProblem(List<ProblemRows.Row> rows, int index) {
        for (int row = 0; row < rows.size(); row++) {
            if (rows.get(row).problemIndex() == index) return row;
        }
        return 0;
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

    private Function<String, Style> roles() {
        ShellThemes active = themes == null ? null : themes.get();
        return role -> active == null ? Style.EMPTY : active.resolver(role).toStyle();
    }

    private String filterLabel() { return switch (filter) { case ALL -> "[A]ll  E:Err  W:Warn"; case ERRORS -> "A:All  [E]rr  W:Warn"; case WARNINGS -> "A:All  E:Err  [W]arn"; }; }
    private String stateLabel() { return switch (state) { case IDLE -> "Idle"; case LOADING -> "Loading…"; case READY -> "Ready"; case EMPTY -> "Empty"; case UNAVAILABLE -> "Unavailable"; case ERROR -> "Error"; }; }
    private static String relativePath(Path root, Path file) {
        Path normalized = file.isAbsolute() ? file.normalize() : root.resolve(file).normalize();
        return normalized.startsWith(root.normalize()) ? root.normalize().relativize(normalized).toString().replace('\\', '/') : normalized.toString().replace('\\', '/');
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String safeMessage(RuntimeException failure) { return failure.getMessage() == null ? failure.toString() : failure.getMessage(); }
}
