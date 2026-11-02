package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Problems browser backed by the selected diagnostics capability. */
@Singleton
public final class ProblemsToolWindow implements ToolWindow {

    private enum Filter { ALL, ERRORS, WARNINGS }
    private enum State { IDLE, LOADING, READY, EMPTY, UNAVAILABLE, ERROR }

    private record DisplayRow(String text, int problemIndex) {}

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Provider<ShellThemes> themes;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("problems-refresh-", 0).factory());
    private final AtomicLong refreshSequence = new AtomicLong();
    private final List<Diagnostic> problems = new ArrayList<>();
    private final List<DisplayRow> displayRows = new ArrayList<>();
    private final List<Integer> visibleProblems = new ArrayList<>();
    private Consumer<Diagnostic> navigateHandler;
    private State state = State.IDLE;
    private Filter filter = Filter.ALL;
    private String message = "Open one or more files in the editor, then refresh";
    private String provenance = "";
    private int selectedIndex;
    private int scrollOffset;
    private int viewportRows = 1;
    private Rect lastArea = new Rect(0, 0, 0, 0);

    @Inject
    public ProblemsToolWindow(CapabilityRegistry registry, BuiltinEditor editor, Provider<ShellThemes> themes) {
        this.registry = registry;
        this.editor = editor;
        this.themes = themes;
    }

    public ProblemsToolWindow(Provider<ShellThemes> themes) {
        this(null, null, themes);
    }

    public ProblemsToolWindow(ShellThemes themes) {
        this(null, null, () -> themes);
    }

    @Override public String id() { return "problems"; }
    @Override public String title() { return "Problems"; }
    @Override public char shortcut() { return '3'; }
    @Override public int ordinal() { return 2; }

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
        if (!event.isClick() || !inside(event.x(), event.y())) return null;
        int row = scrollOffset + event.y() - lastArea.y() - 1;
        if (row < 0 || row >= displayRows.size()) return null;
        int problemIndex = displayRows.get(row).problemIndex();
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
        scrollOffset = clamp(scrollOffset + direction * 3, 0, maxScroll());
        return true;
    }

    @Override
    public void render(Frame frame, Rect area) {
        List<String> lines;
        String title;
        Style style;
        synchronized (this) {
            lastArea = area;
            if (state == State.IDLE) refresh();
            rebuildRows();
            viewportRows = Math.max(1, area.height() - 2);
            scrollOffset = clamp(scrollOffset, 0, maxScroll());
            lines = displayRows.stream().skip(scrollOffset).limit(viewportRows)
                    .map(row -> singleLine(row.text())).toList();
            if (lines.isEmpty()) lines = List.of(message);
            title = "Problems  " + stateLabel() + "  " + filterLabel()
                    + "  R:Refresh" + (provenance.isBlank() ? "" : "  " + provenance);
            ShellThemes activeThemes = themes == null ? null : themes.get();
            style = activeThemes == null ? Style.EMPTY : activeThemes.resolver("tool-window").toStyle();
            Title blockTitle = Title.from(Span.styled(title,
                    activeThemes == null ? Style.EMPTY : activeThemes.resolver("tool-window-title").toStyle()));
            Block.Builder block = Block.builder().title(blockTitle).borders(Borders.ALL)
                    .borderType(BorderType.ROUNDED).style(style);
            if (activeThemes != null) activeThemes.resolver("tool-window").borderColor().ifPresent(block::borderColor);
            frame.renderWidget(Paragraph.builder().text(Text.from(String.join("\n", lines))).style(style)
                    .block(block.build()).build(), area);
            return;
        }
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
        provenance = "";
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
                provenance = provider.provenance();
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
                            + (failure.detail().isBlank() ? "" : " — " + failure.detail())));
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
            provenance = provider.provenance();
            selectedIndex = 0;
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
        message = reason;
    }

    private synchronized void finishError(long request, String reason) {
        if (request != refreshSequence.get()) return;
        state = State.ERROR;
        message = "Diagnostics refresh failed: " + reason;
    }

    private void rebuildRows() {
        displayRows.clear();
        visibleProblems.clear();
        if (state == State.LOADING || state == State.UNAVAILABLE || state == State.ERROR || state == State.IDLE) {
            displayRows.add(new DisplayRow(message, -1));
            return;
        }
        Map<String, List<Integer>> byFile = new LinkedHashMap<>();
        for (int index = 0; index < problems.size(); index++) {
            Diagnostic problem = problems.get(index);
            if (filter == Filter.ERRORS && problem.severity() != Severity.ERROR) continue;
            if (filter == Filter.WARNINGS && problem.severity() != Severity.WARNING) continue;
            String file = relativePath(editor.project(), problem.filePath());
            byFile.computeIfAbsent(file, ignored -> new ArrayList<>()).add(index);
            visibleProblems.add(index);
        }
        if (byFile.isEmpty()) {
            displayRows.add(new DisplayRow(state == State.EMPTY ? message : "No problems match this filter", -1));
            return;
        }
        if (!message.isBlank()) displayRows.add(new DisplayRow("! " + message, -1));
        for (Map.Entry<String, List<Integer>> entry : byFile.entrySet()) {
            displayRows.add(new DisplayRow(entry.getKey(), -1));
            for (int index : entry.getValue()) {
                Diagnostic diagnostic = problems.get(index);
                String line = String.format(Locale.ROOT, "  %s  L%d  %s  %s",
                        severityLabel(diagnostic.severity()), diagnostic.startLine() + 1,
                        diagnostic.source().isBlank() ? "diagnostic" : diagnostic.source(), diagnostic.message());
                if (index == selectedIndex) line = ">" + line.substring(1);
                displayRows.add(new DisplayRow(line, index));
            }
        }
        if (!visibleProblems.isEmpty() && !visibleProblems.contains(selectedIndex)) {
            selectedIndex = visibleProblems.getFirst();
        }
    }

    private void moveSelection(int delta) {
        rebuildRows();
        if (visibleProblems.isEmpty()) return;
        int current = visibleProblems.indexOf(selectedIndex);
        int target = clamp(current + delta, 0, visibleProblems.size() - 1);
        selectedIndex = visibleProblems.get(target);
        rebuildRows();
        int row = rowForProblem(selectedIndex);
        if (row < scrollOffset) scrollOffset = row;
        else if (row >= scrollOffset + viewportRows) scrollOffset = row - viewportRows + 1;
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
        rebuildRows();
    }

    private int rowForProblem(int index) {
        for (int row = 0; row < displayRows.size(); row++) {
            if (displayRows.get(row).problemIndex() == index) return row;
        }
        return 0;
    }

    private int maxScroll() { return Math.max(0, displayRows.size() - viewportRows); }
    private boolean inside(int x, int y) {
        return x >= lastArea.x() && x < lastArea.x() + lastArea.width()
                && y > lastArea.y() && y < lastArea.y() + lastArea.height() - 1;
    }
    private String filterLabel() { return switch (filter) { case ALL -> "[A]ll  E:Err  W:Warn"; case ERRORS -> "A:All  [E]rr  W:Warn"; case WARNINGS -> "A:All  E:Err  [W]arn"; }; }
    private String stateLabel() { return switch (state) { case IDLE -> "Idle"; case LOADING -> "Loading…"; case READY -> "Ready"; case EMPTY -> "Empty"; case UNAVAILABLE -> "Unavailable"; case ERROR -> "Error"; }; }
    private static String severityLabel(Severity severity) { return switch (severity) { case ERROR -> "ERROR"; case WARNING -> "WARN "; case INFORMATION -> "INFO "; case HINT -> "HINT "; }; }
    private static String relativePath(Path root, Path file) {
        Path normalized = file.isAbsolute() ? file.normalize() : root.resolve(file).normalize();
        return normalized.startsWith(root.normalize()) ? root.normalize().relativize(normalized).toString().replace('\\', '/') : normalized.toString().replace('\\', '/');
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String safeMessage(RuntimeException failure) { return failure.getMessage() == null ? failure.toString() : failure.getMessage(); }
    private static String singleLine(String value) {
        return value.replaceAll("\\R", " ").replaceAll("\\p{Cc}", " ").replaceAll("\\s+", " ").strip();
    }
}
