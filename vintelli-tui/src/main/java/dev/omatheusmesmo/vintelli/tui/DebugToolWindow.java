package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugFrame;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugScope;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugVariable;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugThread;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSession;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSessionSnapshot;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.annotation.PreDestroy;

/** Bounded, on-demand debugger inspection view. Painting only reads cached snapshots. */
@Singleton
public final class DebugToolWindow implements ToolWindow {
    private static final int MAX_VARIABLE_DEPTH = 16;
    private static final int MAX_VARIABLE_ROWS = 200;
    private final Provider<ShellThemes> themes;
    private final RunToolWindow run;
    private final UiWorkQueue uiWork;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofVirtual().factory(), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicLong generation = new AtomicLong();
    private volatile String status = "No debug session is active";
    private volatile List<DebugThread> threads = List.of();
    private volatile List<DebugFrame> frames = List.of();
    private volatile List<DebugScope> scopes = List.of();
    private final Map<Integer, List<DebugVariable>> variableCache = new HashMap<>();
    private final Set<Integer> expandedReferences = new HashSet<>();
    private volatile int threadIndex;
    private volatile int frameIndex;
    private volatile int selection;
    private volatile Future<?> pending;
    private volatile String selectedSessionId = "";
    private volatile long selectedInspectionGeneration = -1;

    @Inject
    public DebugToolWindow(Provider<ShellThemes> themes, RunToolWindow run, UiWorkQueue uiWork) {
        this.themes = themes; this.run = run; this.uiWork = uiWork;
    }

    @Override public String id() { return "debug"; }
    @Override public String title() { return "Debug"; }
    @Override public char shortcut() { return '3'; }
    @Override public int ordinal() { return 1; }

    @Override public boolean capturesInput() { return true; }
    @Override public boolean claimsQuitChord() { return false; }

    @PreDestroy void close() { requests.shutdownNow(); }

    String statusMessage() { return status; }

    @Override public boolean handleKey(KeyEvent event) {
        List<String> rows = renderModel(Rect.ZERO).rows();
        if (event.isKey(KeyCode.UP) || event.isCharIgnoreCase('k')) { selection = Math.max(0, selection - 1); return true; }
        if (event.isKey(KeyCode.DOWN) || event.isCharIgnoreCase('j')) { selection = Math.min(Math.max(0, rows.size()-1), selection + 1); return true; }
        if (event.isKey(KeyCode.ENTER) || event.isCharIgnoreCase(' ')) { activateSelected(rows); return true; }
        if (event.isCharIgnoreCase('r')) { refresh(); return true; }
        return false;
    }

    @Override public boolean handleMouse(MouseEvent event) {
        return handleMouse(event, Rect.ZERO);
    }

    @Override public boolean handleMouse(MouseEvent event, Rect area) {
        RenderModel model = renderModel(area);
        if (event.isScroll() && (event.kind() == MouseEventKind.SCROLL_UP || event.kind() == MouseEventKind.SCROLL_DOWN)) {
            if (model.rows().isEmpty()) return false;
            selection = Math.clamp(selection + (event.kind() == MouseEventKind.SCROLL_UP ? -3 : 3), 0, model.rows().size() - 1);
            return true;
        }
        if (!event.isClick() || !event.isLeftButton() || event.y() < area.y() || event.y() >= area.bottom()) return false;
        int line = event.y() - area.y() + model.scrollOffset();
        int row = model.rowLineIndexes().indexOf(line);
        if (row < 0) return false;
        selection = row;
        activateSelected(model.rows());
        return true;
    }

    private void refresh() {
        DebugSession session = run.activeDebugSession();
        if (session == null) {
            cancelPending(); generation.incrementAndGet(); selectedSessionId = ""; selectedInspectionGeneration = -1;
            status = "No debug session is active"; threads = List.of(); frames = List.of(); clearInspection();
            return;
        }
        DebugSessionSnapshot snapshot = session.snapshot();
        if (!snapshot.id().equals(selectedSessionId)) {
            cancelPending(); generation.incrementAndGet(); selectedSessionId = snapshot.id();
            selectedInspectionGeneration = -1;
            threads = List.of(); frames = List.of(); clearInspection(); threadIndex = frameIndex = selection = 0;
        }
        if (snapshot.state() != DebugCapability.State.STOPPED) {
            cancelPending(); generation.incrementAndGet();
            selectedInspectionGeneration = -1;
            threads = List.of(); frames = List.of(); clearInspection();
            status = snapshot.state() == DebugCapability.State.RUNNING || snapshot.state() == DebugCapability.State.EXECUTING
                    ? "Running - pause to inspect" : "Session " + snapshot.state().name().toLowerCase();
            return;
        }
        long inspectionGeneration = session.inspectionGeneration();
        if (selectedInspectionGeneration != inspectionGeneration) {
            cancelPending(); generation.incrementAndGet();
            selectedInspectionGeneration = inspectionGeneration;
            threads = List.of(); frames = List.of(); clearInspection();
            threadIndex = frameIndex = selection = 0;
        }
        status = "Loading threads...";
        long token = generation.incrementAndGet();
        request(session, token, inspectionGeneration, () -> session.threads(), result -> {
            if (result instanceof CapabilityResult.Ok<List<DebugThread>> ok) {
                threads = ok.value().stream().limit(100).toList();
                int stopped = snapshot.stoppedThreadId();
                threadIndex = 0;
                for (int i = 0; i < threads.size(); i++) if (threads.get(i).id() == stopped) { threadIndex = i; break; }
                loadFrames(session, token, inspectionGeneration);
            } else status = failure(result);
        });
    }

    private void loadFrames(DebugSession session, long token, long inspectionGeneration) {
        if (threads.isEmpty()) { status = "No threads"; return; }
        status = "Loading stack...";
        int threadId = threads.get(Math.min(threadIndex, threads.size()-1)).id();
        request(session, token, inspectionGeneration, () -> session.stackTrace(threadId), result -> {
            if (result instanceof CapabilityResult.Ok<List<DebugFrame>> ok) {
                frames = ok.value().stream().limit(200).toList();
                if (frames.isEmpty()) { status = "No stack frames"; return; }
                frameIndex = 0; loadScopes(session, token, inspectionGeneration);
            } else status = failure(result);
        });
    }

    private void loadScopes(DebugSession session, long token, long inspectionGeneration) {
        clearInspection();
        DebugFrame frame = frames.get(Math.min(frameIndex, frames.size()-1));
        // DAP frame identifiers are provider-owned; selectFrame updates the editor and the
        // provider's current frame while stack metadata stays protocol-neutral here.
        status = "Loading scopes...";
        request(session, token, inspectionGeneration, () -> session.selectFrame(frame, inspectionGeneration), result -> {
            if (!(result instanceof CapabilityResult.Ok<Boolean>)) { status = failure(result); return; }
            var bridge = run.debugEditorBridge();
            if (bridge != null) bridge.updateExecutionLocation(frame);
            request(session, token, inspectionGeneration, () -> session.scopes(frame.frameId()), scopeResult -> {
                if (scopeResult instanceof CapabilityResult.Ok<List<DebugScope>> ok) {
                    scopes = ok.value().stream().limit(32).toList(); status = scopes.isEmpty() ? "No scopes" : "Scopes loaded";
                } else status = failure(scopeResult);
            });
        });
    }

    private <T> void request(DebugSession expectedSession, long token, long expectedInspectionGeneration,
            Callable<CapabilityResult<T>> call, Consumer<CapabilityResult<T>> done) {
        cancelPending();
        pending = requests.submit(() -> {
            if (expectedSession == null || generation.get() != token || run.activeDebugSession() != expectedSession
                    || expectedSession.inspectionGeneration() != expectedInspectionGeneration
                    || expectedSession.snapshot().state() != DebugCapability.State.STOPPED) return;
            CapabilityResult<T> result;
            try { result = call.call(); }
            catch (Exception failure) { result = CapabilityResult.failed(new CapabilityFailure(
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), "")); }
            CapabilityResult<T> completed = result;
            uiWork.execute(() -> {
                if (generation.get() == token && run.activeDebugSession() == expectedSession
                        && expectedSession.snapshot().id().equals(selectedSessionId)
                        && expectedSession.snapshot().state() == DebugCapability.State.STOPPED
                        && expectedSession.inspectionGeneration() == expectedInspectionGeneration) {
                    done.accept(completed);
                }
            });
        });
    }

    private void cancelPending() { Future<?> current = pending; if (current != null) current.cancel(true); }

    private void activateSelected(List<String> currentRows) {
        DebugSession session = run.activeDebugSession();
        if (session == null || currentRows.isEmpty()) return;
        if (!session.snapshot().id().equals(selectedSessionId)
                || session.inspectionGeneration() != selectedInspectionGeneration) {
            refresh();
            return;
        }
        if (selection >= currentRows.size()) return;
        String row = currentRows.get(selection);
        if (row.startsWith("thread:")) {
            threadIndex = Integer.parseInt(row.substring(7)); frames = List.of(); clearInspection();
            long token = generation.incrementAndGet(); loadFrames(session, token, selectedInspectionGeneration);
        } else if (row.startsWith("frame:")) {
            frameIndex = Integer.parseInt(row.substring(6));
            loadScopes(session, generation.incrementAndGet(), selectedInspectionGeneration);
        } else if (row.startsWith("scope:")) {
            int index = Integer.parseInt(row.substring(6));
            if (index < scopes.size()) toggleVariables(session, scopes.get(index).variablesReference());
        } else if (row.startsWith("variable:")) {
            int reference = Integer.parseInt(row.substring(9));
            toggleVariables(session, reference);
        }
    }

    private void toggleVariables(DebugSession session, int reference) {
        if (reference <= 0) return;
        if (expandedReferences.contains(reference)) { expandedReferences.remove(reference); return; }
        if (variableCache.containsKey(reference)) { expandedReferences.add(reference); return; }
        loadVariables(session, reference, generation.incrementAndGet());
    }

    private void loadVariables(DebugSession session, int reference, long token) {
        if (reference <= 0) return;
        long inspectionGeneration = session.inspectionGeneration();
        status = "Loading variables...";
        request(session, token, inspectionGeneration, () -> session.variables(reference), result -> {
            if (result instanceof CapabilityResult.Ok<List<DebugVariable>> ok) {
                List<DebugVariable> loaded = ok.value().stream().limit(200).toList();
                variableCache.put(reference, loaded); expandedReferences.add(reference);
                status = loaded.isEmpty() ? "No variables" : "Variables loaded";
            } else status = failure(result);
        });
    }

    private void clearInspection() {
        scopes = List.of(); variableCache.clear(); expandedReferences.clear();
    }

    private static String failure(CapabilityResult<?> result) {
        if (result instanceof CapabilityResult.Failed<?> failed) return "Inspection failed: " + failed.failure().reason();
        return "Inspection failed";
    }

    private RenderModel renderModel(Rect area) {
        DebugSession session = run.activeDebugSession();
        String headline = "Unavailable - start or attach a debug session";
        DebugSessionSnapshot snapshot = session == null ? null : session.snapshot();
        long inspectionGeneration = session == null ? -1 : session.inspectionGeneration();
        if (session != null) {
            headline = switch (snapshot.state()) {
                case RUNNING, EXECUTING -> "Running - pause to inspect";
                case FAILED -> "Failed - " + snapshot.reason();
                case TERMINATED, DISCONNECTED -> "No active debug session";
                case STOPPED -> snapshot.id().equals(selectedSessionId)
                        && selectedInspectionGeneration == inspectionGeneration
                        ? status : "Stopped - press r to inspect";
                default -> "Loading debug session...";
            };
        }
        List<String> lines = new ArrayList<>(); lines.add(headline);
        List<Integer> selectableLines = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        boolean inspectable = session != null && snapshot.state() == DebugCapability.State.STOPPED
                && snapshot.id().equals(selectedSessionId) && selectedInspectionGeneration == inspectionGeneration;
        if (session != null) {
            lines.add("Session: " + snapshot.configurationName() + "  State: " + snapshot.state());
            if (!snapshot.stoppedReason().isBlank()) lines.add("Reason: " + snapshot.stoppedReason());
        }
        lines.add("Threads");
        List<DebugThread> visibleThreads = inspectable ? threads : List.of();
        List<DebugFrame> visibleFrames = inspectable ? frames : List.of();
        List<DebugScope> visibleScopes = inspectable ? scopes : List.of();
        int[] remainingVariableRows = {MAX_VARIABLE_ROWS};
        for (int i = 0; i < visibleThreads.size(); i++) { rows.add("thread:" + i); selectableLines.add(lines.size()); lines.add("  " + visibleThreads.get(i).name()); }
        lines.add("Call stack");
        for (int i = 0; i < visibleFrames.size(); i++) { rows.add("frame:" + i); selectableLines.add(lines.size()); DebugFrame f=visibleFrames.get(i); lines.add("  " + f.name() + " @ " + f.sourcePath() + ":" + f.line()); }
        for (int i = 0; i < visibleScopes.size(); i++) {
            DebugScope scope = visibleScopes.get(i); rows.add("scope:" + i); selectableLines.add(lines.size());
            lines.add("  " + (expandedReferences.contains(scope.variablesReference()) ? "[-] " : scope.variablesReference() > 0 ? "[+] " : "    ") + scope.name());
            appendVariables(lines, selectableLines, rows, scope.variablesReference(), 1,
                    variableCache, expandedReferences, new HashSet<>(), remainingVariableRows);
        }
        lines.add("j/k select  Enter inspect  r refresh");
        int currentSelection = Math.clamp(selection, 0, Math.max(0, rows.size() - 1));
        if (!rows.isEmpty()) {
            int selectedLine = selectableLines.get(currentSelection);
            lines.set(selectedLine, "> " + lines.get(selectedLine).substring(2));
        }
        int viewport = Math.max(1, area.height());
        int selectedLine = currentSelection < selectableLines.size() ? selectableLines.get(currentSelection) : 0;
        int scrollOffset = Math.clamp(Math.max(0, selectedLine - viewport + 1), 0, Math.max(0, lines.size() - viewport));
        List<String> visible = lines.subList(scrollOffset, Math.min(lines.size(), scrollOffset + viewport));
        return new RenderModel(List.copyOf(visible), List.copyOf(rows), List.copyOf(selectableLines), scrollOffset);
    }

    @Override public void render(Frame frame, Rect area) {
        RenderModel model = renderModel(area);
        ShellThemes active = themes.get(); Style style = active.resolver("tool-window").toStyle();
        frame.renderWidget(Paragraph.builder().text(Text.from(String.join("\n", model.visibleLines()))).style(style).build(), area);
    }

    static void appendVariables(List<String> lines, List<Integer> selectableLines, List<String> rowIds,
            int reference, int depth, Map<Integer, List<DebugVariable>> cache,
            Set<Integer> expanded, Set<Integer> path, int[] remainingRows) {
        if (!expanded.contains(reference) || remainingRows[0] < 0) return;
        if (depth >= MAX_VARIABLE_DEPTH) {
            appendMarker(lines, depth, "maximum depth reached", remainingRows);
            return;
        }
        if (!path.add(reference)) {
            appendMarker(lines, depth, "cycle to reference " + reference, remainingRows);
            return;
        }
        for (DebugVariable child : cache.getOrDefault(reference, List.of())) {
            if (remainingRows[0] <= 0) {
                appendMarker(lines, depth, "variable limit reached", remainingRows);
                break;
            }
            rowIds.add("variable:" + child.variablesReference()); selectableLines.add(lines.size());
            lines.add("  " + "  ".repeat(depth + 1)
                    + (child.hasChildren() ? expanded.contains(child.variablesReference()) ? "[-] " : "[+] " : "    ")
                    + child.name() + " = " + child.value() + (child.type().isBlank() ? "" : " : " + child.type()));
            remainingRows[0]--;
            if (child.hasChildren() && expanded.contains(child.variablesReference())) {
                if (remainingRows[0] <= 0) {
                    appendMarker(lines, depth + 1, "variable limit reached", remainingRows);
                    break;
                }
                appendVariables(lines, selectableLines, rowIds, child.variablesReference(), depth + 1,
                        cache, expanded, path, remainingRows);
            }
        }
        path.remove(reference);
    }

    private static void appendMarker(List<String> lines, int depth, String message, int[] remainingRows) {
        if (remainingRows[0] < 0) return;
        lines.add("  " + "  ".repeat(depth + 1) + "... [" + message + "]");
        remainingRows[0]--;
    }

    private record RenderModel(List<String> visibleLines, List<String> rows, List<Integer> rowLineIndexes,
            int scrollOffset) { }

    @Override public String actionForMouse(MouseEvent event) { return null; }
}
