package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns the editor island: the open tabs and the commands on the active
 * document, the breadcrumb navigation with its document symbols, opening and
 * revealing files, the editor actions of the global keymap and the pushed
 * highlight and diff. The shell hands the effects that leave the island
 * (status, focus, project tree, version control refresh) through {@link Host}.
 * It holds no TamboUI type: {@link EditorPanel} draws the state and forwards
 * the events.
 */
final class EditorController {

    /** What the editor reads from the shell and what its commands change there. */
    interface Host {

        void status(String message);

        NavigationCapability navigationProvider();

        void refreshVcs();

        void refreshProjectTree();

        void requestProjectFocus();

        void releaseCapture();

        void takeFocus(String elementId);
    }

    private final Path projectRoot;
    private final BuiltinEditor editor;
    private final ProjectTreeController projects;
    private final Host host;
    private volatile ShellHighlightTarget highlight;
    private volatile ShellUnifiedDiff diff;
    private boolean navigationBarFocused;
    private int selectedNavigationSegment;
    private List<StatusBar.Breadcrumb> navigationPaths = List.of();
    private Path navigationSymbolsFile;
    private List<NavigationCapability.DocumentSymbol> navigationSymbols = List.of();
    private int nextDiagnosticIndex;

    EditorController(Path projectRoot, BuiltinEditor editor, ProjectTreeController projects, Host host) {
        this.projectRoot = projectRoot;
        this.editor = editor;
        this.projects = projects;
        this.host = host;
    }

    List<StatusBar.Breadcrumb> navigationSegments() {
        return navigationPaths;
    }

    boolean navigationBarFocused() {
        return navigationBarFocused;
    }

    StatusBar.Navigation statusNavigation() {
        return new StatusBar.Navigation(navigationPaths, selectedNavigationSegment, navigationBarFocused);
    }

    ShellHighlightTarget pushedHighlight() {
        return highlight;
    }

    ShellUnifiedDiff pushedDiff() {
        return diff;
    }

    void pushHighlight(ShellHighlightTarget target) {
        Objects.requireNonNull(target, "target");
        highlight = target;
    }

    void pushDiff(ShellUnifiedDiff unifiedDiff) {
        Objects.requireNonNull(unifiedDiff, "unifiedDiff");
        diff = unifiedDiff;
    }

    /**
     * Routes a click on the editor bar: a tab selects its document and focuses
     * the text.
     *
     * @return true when the click landed on a tab
     */
    boolean clickEditorBar(int x, int y) {
        if (editor == null) {
            return false;
        }
        int tab = editor.tabAt(x, y);
        if (tab >= 0) {
            editor.selectTab(tab);
            editor.requestFocus();
            host.status("Editor tab: " + editor.activeDocument().orElseThrow().file());
            return true;
        }
        return false;
    }

    /**
     * Rebuilds the breadcrumb segments of the active document. The editor content
     * and the status bar call it while they render, under the frame lock, so the
     * segments change in the same pass that draws them.
     */
    void refreshNavigation() {
        if (editor == null || editor.activeDocument().isEmpty()) {
            navigationPaths = List.of();
            return;
        }
        Path file = Path.of(editor.activeDocument().orElseThrow().file()).toAbsolutePath().normalize();
        BuiltinEditor.DocumentView document = editor.activeDocument().orElseThrow();
        Path root = projectRoot.toAbsolutePath().normalize();
        try {
            Path relative = root.relativize(file);
            List<StatusBar.Breadcrumb> paths = new ArrayList<>(StatusBar.pathBreadcrumbs(root, relative));
            if (!file.equals(navigationSymbolsFile)) {
                navigationSymbolsFile = file;
                navigationSymbols = List.of();
                NavigationCapability provider = host.navigationProvider();
                if (provider != null) {
                    try {
                        CapabilityResult<List<NavigationCapability.DocumentSymbol>> result = provider.documentSymbols(file);
                        if (result instanceof CapabilityResult.Ok<List<NavigationCapability.DocumentSymbol>> ok) {
                            navigationSymbols = ok.value();
                        }
                    } catch (RuntimeException unavailable) {
                        navigationSymbols = List.of();
                    }
                }
            }
            List<NavigationCapability.DocumentSymbol> containing = new ArrayList<>();
            collectContainingSymbols(navigationSymbols, document.line(), containing);
            paths.addAll(StatusBar.symbolBreadcrumbs(file, containing));
            navigationPaths = List.copyOf(paths);
            selectedNavigationSegment = Math.clamp(selectedNavigationSegment, 0, paths.size() - 1);
            if (!navigationBarFocused) {
                selectedNavigationSegment = paths.size() - 1;
            }
        } catch (IllegalArgumentException outsideProject) {
            navigationPaths = List.of();
        }
    }

    void focusNavigationBar() {
        refreshNavigation();
        if (navigationPaths.isEmpty()) {
            host.status("Open a project file to navigate its path");
            return;
        }
        navigationBarFocused = true;
        host.takeFocus(StatusBarElement.ID);
        host.status("Navigation bar: arrows move, Enter opens, Escape returns to editor");
    }

    void leaveNavigationBar() {
        navigationBarFocused = false;
    }

    void returnToEditor() {
        navigationBarFocused = false;
        editor.requestFocus();
    }

    void previousNavigationSegment() {
        selectedNavigationSegment = Math.max(0, selectedNavigationSegment - 1);
    }

    void nextNavigationSegment() {
        selectedNavigationSegment = Math.min(navigationPaths.size() - 1, selectedNavigationSegment + 1);
    }

    /**
     * Opens the breadcrumb segment that the bar cursor is on.
     *
     * @return false when there is no segment to open
     */
    boolean openSelectedSegment() {
        if (navigationPaths.isEmpty()) {
            return false;
        }
        navigateToBreadcrumb(selectedNavigationSegment);
        return true;
    }

    void navigateToBreadcrumb(int segment) {
        if (segment < 0 || segment >= navigationPaths.size()) {
            return;
        }
        StatusBar.Breadcrumb breadcrumb = navigationPaths.get(segment);
        Path path = breadcrumb.path();
        navigationBarFocused = false;
        if (breadcrumb.line() >= 0) {
            editor.open(new OpenRequest(path, breadcrumb.line()));
            editor.requestFocus();
        } else if (Files.isDirectory(path)) {
            Path relative = projectRoot.relativize(path);
            projects.expandPrefixes(relative);
            host.refreshProjectTree();
            projects.selectPath(relative.toString().replace('\\', '/'));
            host.requestProjectFocus();
        } else {
            revealFile(path);
            host.requestProjectFocus();
        }
    }

    private static void collectContainingSymbols(List<NavigationCapability.DocumentSymbol> symbols, int line,
            List<NavigationCapability.DocumentSymbol> result) {
        for (NavigationCapability.DocumentSymbol symbol : symbols) {
            if (line >= symbol.startLine() && line <= symbol.endLine()) {
                result.add(symbol);
                collectContainingSymbols(symbol.children(), line, result);
                return;
            }
        }
    }

    void openFile(Path file, int line) {
        if (editor == null) {
            host.status("Built-in editor unavailable");
            return;
        }
        if (editor.open(new OpenRequest(file, line)).isFailure()) {
            host.status(editor.status());
            return;
        }
        host.refreshVcs();
        host.status(editor.status());
    }

    void openRevealed(Path file, int line) {
        revealFile(file);
        openFile(file, line);
    }

    void openSelectedFile() {
        projects.activateSelected().ifPresent(file -> openFile(file, 0));
    }

    void openActiveFileLine(int line) {
        if (editor == null) {
            return;
        }
        editor.activeDocument().ifPresent(document -> {
            host.releaseCapture();
            openFile(Path.of(document.file()), line);
        });
    }

    void openDiagnostic(Diagnostic problem) {
        if (editor == null) {
            host.status("Built-in editor unavailable");
            return;
        }
        try {
            Path root = editor.project().toRealPath();
            Path file = problem.filePath().isAbsolute() ? problem.filePath() : root.resolve(problem.filePath());
            file = file.toRealPath();
            if (!file.startsWith(root)) {
                host.status("Diagnostic file is outside the project");
                return;
            }
            revealFile(file);
            host.releaseCapture();
            openFile(file, problem.startLine());
        } catch (IOException unavailable) {
            host.status("Diagnostic file is unavailable");
        }
    }

    void revealFile(Path file) {
        if (file == null) {
            return;
        }
        String relative = projects.expandToFile(file);
        if (relative != null) {
            host.refreshProjectTree();
            projects.selectPath(relative);
        }
    }

    void revealActiveFile() {
        if (editor == null || editor.activeDocument() == null) {
            host.status("No active file");
            return;
        }
        revealFile(Path.of(editor.activeDocument().orElseThrow().file()));
    }

    void navigateToSymbol(boolean references) {
        if (editor == null || editor.activeDocument().isEmpty()) {
            host.status("Open a project file first");
            return;
        }
        NavigationCapability provider = host.navigationProvider();
        if (provider == null) {
            host.status("Navigation provider unavailable");
            return;
        }
        BuiltinEditor.DocumentView document = editor.activeDocument().orElseThrow();
        NavigationCapability.SourcePosition position = new NavigationCapability.SourcePosition(
                Path.of(document.file()), document.line(), document.column());
        CapabilityResult<List<NavigationCapability.Location>> result = references
                ? provider.references(position, true) : provider.definition(position);
        if (result instanceof CapabilityResult.Ok<List<NavigationCapability.Location>> ok && !ok.value().isEmpty()) {
            NavigationCapability.Location location = ok.value().getFirst();
            openRevealed(location.filePath(), location.line());
        } else {
            host.status(result.failureOf().map(failure -> failure.reason() + ": " + failure.detail())
                    .orElse(references ? "No usages found" : "No definition found"));
        }
    }

    void closeActiveTab() {
        if (editor == null) {
            host.status("Built-in editor unavailable");
            return;
        }
        BuiltinEditor.CloseResult result = editor.closeActive();
        host.status(result == BuiltinEditor.CloseResult.CONFIRM_REQUIRED ? editor.status()
                : result == BuiltinEditor.CloseResult.EMPTY ? "No editor tab is open" : "Tab closed");
    }

    void selectEditorTab(boolean next) {
        if (editor == null || editor.tabs().isEmpty()) {
            host.status("No editor tab is open");
            return;
        }
        if (next) {
            editor.nextTab();
        } else {
            editor.previousTab();
        }
        host.status("Editor tab: " + editor.activeDocument().orElseThrow().file());
    }

    void saveActiveFile() {
        if (editor == null) {
            host.status("Built-in editor unavailable");
            return;
        }
        editor.save();
        host.refreshVcs();
        host.status(editor.status());
    }

    private void runClipboardAction(Runnable action) {
        if (editor == null) {
            host.status("Built-in editor unavailable");
            return;
        }
        action.run();
        host.status(editor.status());
    }

    void editorUndo() {
        if (editor == null || !editor.undo()) {
            host.status(editor == null ? "Built-in editor unavailable" : "Nothing to undo");
        } else {
            host.status("Undo");
        }
    }

    void editorRedo() {
        if (editor == null || !editor.redo()) {
            host.status(editor == null ? "Built-in editor unavailable" : "Nothing to redo");
        } else {
            host.status("Redo");
        }
    }

    void toggleImportFold() {
        if (editor == null) {
            host.status("Built-in editor unavailable");
        } else {
            editor.toggleImportFold();
            host.status(editor.status());
        }
    }

    /**
     * Runs an editor action of the global keymap. The overlays close before the
     * action, as every shell command does. Returns false for every other action.
     */
    boolean handle(String actionId, Runnable closeOverlays) {
        return switch (actionId) {
            case KeyActions.FOCUS_NAVIGATION_BAR -> {
                closeOverlays.run();
                focusNavigationBar();
                yield true;
            }
            case KeyActions.OPEN_FILE -> {
                closeOverlays.run();
                openSelectedFile();
                yield true;
            }
            case KeyActions.SAVE -> {
                closeOverlays.run();
                saveActiveFile();
                yield true;
            }
            case KeyActions.UNDO -> {
                closeOverlays.run();
                editorUndo();
                yield true;
            }
            case KeyActions.REDO -> {
                closeOverlays.run();
                editorRedo();
                yield true;
            }
            case KeyActions.CUT -> {
                closeOverlays.run();
                runClipboardAction(editor == null ? null : editor::cutSelection);
                yield true;
            }
            case KeyActions.COPY -> {
                closeOverlays.run();
                runClipboardAction(editor == null ? null : editor::copySelection);
                yield true;
            }
            case KeyActions.PASTE -> {
                closeOverlays.run();
                runClipboardAction(editor == null ? null : editor::pasteClipboard);
                yield true;
            }
            case KeyActions.CLOSE_TAB -> {
                closeOverlays.run();
                closeActiveTab();
                yield true;
            }
            case KeyActions.NEXT_TAB -> {
                closeOverlays.run();
                selectEditorTab(true);
                yield true;
            }
            case KeyActions.PREVIOUS_TAB -> {
                closeOverlays.run();
                selectEditorTab(false);
                yield true;
            }
            case KeyActions.DEFINITION -> {
                closeOverlays.run();
                navigateToSymbol(false);
                yield true;
            }
            case KeyActions.USAGES -> {
                closeOverlays.run();
                navigateToSymbol(true);
                yield true;
            }
            case KeyActions.TOGGLE_IMPORT_FOLD -> {
                closeOverlays.run();
                toggleImportFold();
                yield true;
            }
            case KeyActions.NEXT_PROBLEM -> {
                closeOverlays.run();
                nextProblem();
                yield true;
            }
            default -> false;
        };
    }

    private void nextProblem() {
        if (editor == null || editor.diagnostics().isEmpty()) {
            host.status("No problems in the active file");
            return;
        }
        nextDiagnosticIndex = Math.floorMod(nextDiagnosticIndex, editor.diagnostics().size());
        editor.jumpToDiagnostic(nextDiagnosticIndex++);
        host.status(editor.status());
    }
}
