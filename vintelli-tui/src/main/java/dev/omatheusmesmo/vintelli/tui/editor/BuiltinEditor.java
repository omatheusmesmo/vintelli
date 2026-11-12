package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.keymap.ConfigDirectory;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Built-in adapter. All document, element and external inspection access shares this monitor. */
@Singleton
public final class BuiltinEditor implements EditorBridge, ToolWindow {
    public enum CloseResult { CLOSED, CONFIRM_REQUIRED, EMPTY }
    public record TabView(String file, boolean dirty, boolean active) {}
    public record DocumentView(String file, String text, int line, int column, boolean dirty) {}
    public enum PointerGesture { PRESS, DRAG, RELEASE, SCROLL_UP, SCROLL_DOWN }
    /**
     * Caret, selection and viewport after pointer input. Line and column are document positions;
     * the scroll values are the displayed view, which differs from the document after an import fold.
     */
    public record PointerState(int line, int column, int anchorLine, int anchorColumn, String selectedText,
            int scrollRow, int scrollCol) {}
    public record StatusBarInfo(int line, int column, String encoding, String lineSeparator, String indent,
            boolean readOnly) {}
    public static final int TAB_BAR_ROWS = EditorTabBarElement.ROWS;
    public static final String TEXT_AREA_ID = "editor-text-area";
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final Keymap DEFAULT_KEYMAP = Keymap.ofProfile(KeymapProfile.DEFAULT, List.of());
    private final List<EditorDocument> documents = new ArrayList<>();
    private final Supplier<ShellEditorPalette> palette;
    private final Instance<CapabilityRegistry> capabilities;
    private final EditorElement element;
    private Path project;
    private EditorSessionStore sessionStore;
    private BreakpointStore breakpointStore;
    private final Map<Path, Map<Integer, DebugCapability.SourceBreakpoint>> breakpoints = new HashMap<>();
    private final Map<Path, Object> breakpointSyncLocks = new HashMap<>();
    private long breakpointGeneration;
    private int active = -1;
    private Path pendingClose;
    private String status = "Open a file from Project or Go to File";
    private boolean sessionLoaded;
    private boolean menuNavigationActive;
    private String focusRequest;
    private Map<String, VersionControlCapability.State> vcsStates = Map.of();
    private Map<String, Set<Integer>> vcsChangedLines = Map.of();
    private FileIcons icons;
    private final EditorClipboard clipboard;
    private Supplier<Keymap> keymap;

    @Inject
    public BuiltinEditor(@ConfigProperty(name = "vintelli.local.project-root") Optional<String> configuredRoot,
            Instance<ShellThemes> themes, Instance<CapabilityRegistry> capabilities) {
        this(configuredRoot.filter(value -> !value.isBlank()).map(Path::of).orElse(Path.of("")), null,
                () -> themes.get().editorPalette(), capabilities);
        Path config = ConfigDirectory.configRoot(ConfigDirectory.sharedConfigDir(), System.getenv("XDG_CONFIG_HOME"),
                System.getenv("APPDATA"), System.getProperty("user.home"));
        if (config != null) {
            sessionStore = new EditorSessionStore(config.resolve("vintelli/editor-tabs").resolve(projectKey(project) + ".properties"));
            breakpointStore = new BreakpointStore(config.resolve("vintelli/editor-tabs")
                    .resolve(projectKey(project) + ".breakpoints.properties"));
        }
    }

    /** Explicit root and private session path for tests and embedding; null disables persistence. */
    public BuiltinEditor(Path project, Path sessionFile, Supplier<ShellEditorPalette> palette) {
        this(project, sessionFile, palette, new NativeSystemClipboard(System.getenv("PATH")), BuiltinEditor::writeTerminal);
    }

    /**
     * Explicit clipboard and terminal output for tests and embedding. The terminal receives the
     * OSC 52 sequence of each copy; the system clipboard is the platform tool access.
     */
    public BuiltinEditor(Path project, Path sessionFile, Supplier<ShellEditorPalette> palette,
            SystemClipboard systemClipboard, Consumer<String> terminal) {
        this(project, sessionFile, palette, systemClipboard, terminal, null);
    }

    BuiltinEditor(Path project, Path sessionFile, Supplier<ShellEditorPalette> palette,
            Instance<CapabilityRegistry> capabilities) {
        this(project, sessionFile, palette, new NativeSystemClipboard(System.getenv("PATH")),
                BuiltinEditor::writeTerminal, capabilities);
    }

    private BuiltinEditor(Path project, Path sessionFile, Supplier<ShellEditorPalette> palette,
            SystemClipboard systemClipboard, Consumer<String> terminal, Instance<CapabilityRegistry> capabilities) {
        Path absoluteProject = project.toAbsolutePath().normalize();
        try {
            this.project = absoluteProject.toRealPath();
        } catch (IOException unavailable) {
            this.project = absoluteProject;
        }
        this.palette = palette;
        this.capabilities = capabilities;
        sessionStore = sessionFile == null ? null : new EditorSessionStore(sessionFile);
        clipboard = new EditorClipboard(systemClipboard, terminal);
        breakpointStore = sessionFile == null ? null : new BreakpointStore(sessionFile.resolveSibling(
                sessionFile.getFileName() + ".breakpoints"));
        element = new EditorElement(this);
    }

    private static void writeTerminal(String sequence) {
        System.out.print(sequence);
        System.out.flush();
    }

    @Override public String identifier() { return "builtin"; }
    @Override public ProviderHealth health() { return ProviderHealth.available(); }
    @Override public String provenance() { return "built-in TamboUI TextArea editor"; }
    @Override public String id() { return "editor"; }
    @Override public String title() { return "Editor"; }
    @Override public char shortcut() { return '5'; }
    @Override public int ordinal() { return 40; }
    @Override public boolean capturesInput() { return true; }
    @Override public boolean handleKey(KeyEvent key) { return element.handleKeyEvent(key, true).isHandled(); }
    @Override public boolean shouldHandleKeyBeforeShell(KeyEvent key) { return key.isKey(KeyCode.ESCAPE) && findOpen(); }
    @Override public synchronized void render(Frame frame, Rect area) {
        element.render(frame, area, DefaultRenderContext.createEmpty());
    }

    public Element element() { return element; }
    public synchronized boolean hasDocument() {
        ensureSession();
        return document() != null;
    }
    synchronized FileIcons icons() {
        if (icons == null) {
            icons = FileIcons.resolve();
        }
        return icons;
    }
    public synchronized void renderTabBar(Frame frame, Rect area, RenderContext context, boolean focused, String focusMark) {
        element.renderTabBar(frame, area, context, focused, focusMark);
    }
    public synchronized int tabAt(int x, int y) {
        return element.tabAt(x, y);
    }
    public synchronized boolean handlePointer(MouseEvent event) {
        return element.handleMouse(event);
    }
    public synchronized void cancelPointer() {
        element.cancelPointer();
    }
    public synchronized Optional<PointerState> pointerState() {
        EditorDocument document = document();
        if (document == null) {
            return Optional.empty();
        }
        SelectionCursor selection = document.selection();
        return Optional.of(new PointerState(document.row(), document.column(), selection.anchorRow(),
                selection.anchorCol(), document.selectedText(), document.scrollRow(), document.scrollCol()));
    }
    /**
     * Lays the active document out in a viewport of the given size, then routes one pointer gesture
     * through {@link #handlePointer} exactly as the shell does. Used by the gated inspection surface.
     */
    public synchronized Optional<PointerState> routePointer(PointerGesture gesture, int x, int y, int width, int height) {
        Rect area = new Rect(0, 0, width, height);
        render(Frame.forTesting(Buffer.empty(area)), area);
        MouseEvent event = switch (gesture) {
            case PRESS -> MouseEvent.press(MouseButton.LEFT, x, y);
            case DRAG -> MouseEvent.drag(MouseButton.LEFT, x, y);
            case RELEASE -> MouseEvent.release(MouseButton.LEFT, x, y);
            case SCROLL_UP -> MouseEvent.scrollUp(x, y);
            case SCROLL_DOWN -> MouseEvent.scrollDown(x, y);
        };
        handlePointer(event);
        return pointerState();
    }
    public synchronized void menuNavigationActive(boolean active) { menuNavigationActive = active; }
    synchronized boolean menuNavigationActive() { return menuNavigationActive; }
    public synchronized Path project() { return project; }
    public synchronized String status() { return status; }
    synchronized ShellEditorPalette palette() { return palette.get(); }
    synchronized String consumeFocusRequest() {
        String requested = focusRequest;
        focusRequest = null;
        return requested;
    }
    public synchronized void requestFocus() { focusRequest = TEXT_AREA_ID; }
    synchronized EditorDocument document() { return active < 0 ? null : documents.get(active); }

    synchronized Map<Integer, DebugCapability.SourceBreakpoint> breakpoints(EditorDocument document) {
        return Map.copyOf(breakpoints.getOrDefault(document.file(), Map.of()));
    }

    synchronized boolean toggleBreakpoint(int line) {
        EditorDocument document = document();
        if (document == null || line < 0 || line >= document.lineCount()) {
            return false;
        }
        Map<Integer, DebugCapability.SourceBreakpoint> source = breakpoints.computeIfAbsent(document.file(),
                ignored -> new TreeMap<>());
        if (source.remove(line) == null) {
            source.put(line, new DebugCapability.SourceBreakpoint(document.file().toString(), line + 1));
        }
        breakpointGeneration++;
        syncBreakpoints(document.file());
        persistBreakpoints();
        return true;
    }

    public synchronized boolean toggleBreakpointAtCaret() {
        EditorDocument document = document();
        return document != null && toggleBreakpoint(document.row());
    }

    private void syncBreakpoints(Path file) {
        if (capabilities == null) {
            markBreakpointsPending(file, "Debug provider unavailable");
            return;
        }
        try {
            if (!capabilities.isResolvable()) {
                markBreakpointsPending(file, "Debug provider unavailable");
                return;
            }
        } catch (RuntimeException failure) {
            markBreakpointsPending(file, failure.getMessage() == null
                    ? "Debug provider unavailable" : failure.getMessage());
            return;
        }
        Object lock = breakpointSyncLocks.computeIfAbsent(file, ignored -> new Object());
        Thread.ofVirtual().name("vintelli-breakpoint-sync").start(() -> {
            synchronized (lock) {
                try {
                    CapabilitySelection<DebugCapability> selected = capabilities.get().debug();
                    if (selected instanceof CapabilitySelection.Unavailable<DebugCapability> unavailable) {
                        markBreakpointsPending(file, unavailable.reason());
                        return;
                    }
                    if (!(selected instanceof CapabilitySelection.Selected<DebugCapability> available)) {
                        markBreakpointsPending(file, "Debug provider unavailable");
                        return;
                    }
                    List<DebugCapability.SourceBreakpoint> requested;
                    synchronized (BuiltinEditor.this) {
                        requested = breakpoints.getOrDefault(file, Map.of()).values().stream()
                                .sorted(Comparator.comparingInt(DebugCapability.SourceBreakpoint::line)).toList();
                    }
                    CapabilityResult<List<DebugCapability.SourceBreakpoint>> result = available.provider()
                            .setBreakpoints(file.toString(), requested);
                    if (result instanceof CapabilityResult.Ok<List<DebugCapability.SourceBreakpoint>> ok) {
                        Map<Integer, DebugCapability.SourceBreakpoint> synchronizedPoints = new TreeMap<>();
                        for (DebugCapability.SourceBreakpoint point : ok.value()) {
                            synchronizedPoints.put(point.line() - 1, point);
                        }
                        synchronized (BuiltinEditor.this) {
                            List<Integer> currentLines = breakpoints.getOrDefault(file, Map.of()).keySet().stream().sorted().toList();
                            List<Integer> appliedLines = synchronizedPoints.keySet().stream().sorted().toList();
                            if (!currentLines.equals(appliedLines)) {
                                return;
                            }
                            breakpoints.put(file, synchronizedPoints);
                            persistBreakpoints();
                        }
                    } else {
                        markBreakpointsPending(file,
                                ((CapabilityResult.Failed<List<DebugCapability.SourceBreakpoint>>) result).failure().reason());
                    }
                } catch (RuntimeException failure) {
                    markBreakpointsPending(file, failure.getMessage() == null
                            ? "Debug provider unavailable" : failure.getMessage());
                }
            }
        });
    }

    private synchronized void markBreakpointsPending(Path file, String reason) {
        Map<Integer, DebugCapability.SourceBreakpoint> source = breakpoints.get(file);
        if (source == null || source.isEmpty()) {
            return;
        }
        Map<Integer, DebugCapability.SourceBreakpoint> pending = new TreeMap<>();
        source.forEach((line, point) -> pending.put(line, new DebugCapability.SourceBreakpoint(point.sourcePath(),
                point.line(), DebugCapability.BreakpointState.PENDING, reason)));
        breakpoints.put(file, pending);
    }

    private void persistBreakpoints() {
        if (breakpointStore == null) {
            return;
        }
        try {
            List<BreakpointStore.Source> sources = breakpoints.entrySet().stream()
                    .map(source -> new BreakpointStore.Source(source.getKey(),
                            source.getValue().keySet().stream().toList()))
                    .toList();
            breakpointStore.save(project, sources);
        } catch (IOException failure) {
            status = "Cannot persist breakpoints: " + failure.getMessage();
        }
    }

    @Override
    public synchronized CapabilityResult<Opened> open(OpenRequest request) {
        ensureSession();
        return openFile(request, true);
    }

    private CapabilityResult<Opened> openFile(OpenRequest request, boolean persist) {
        try {
            Path root = project.toRealPath();
            Path file = (request.file().isAbsolute() ? request.file() : root.resolve(request.file())).toRealPath();
            if (!file.startsWith(root)) { return failed("The file is outside the project", file.toString()); }
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
                return failed("Only regular text files up to 4 MiB can be opened", file.toString());
            }
            int existing = -1;
            for (int i = 0; i < documents.size(); i++) { if (documents.get(i).file().equals(file)) { existing = i; break; } }
            if (existing < 0) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.indexOf('\0') >= 0) { return failed("Binary files cannot be edited", file.toString()); }
                if (hasMixedLineEndings(text)) {
                    return failed("Files with mixed line endings cannot be edited safely", file.toString());
                }
                documents.add(new EditorDocument(file, text));
                existing = documents.size() - 1;
            }
            active = existing;
            cancelPointer();
            focusRequest = TEXT_AREA_ID;
            EditorDocument document = document();
            document.position(request.line(), 0);
            status = "Opened " + project.relativize(file);
            if (persist) { persist(); }
            return CapabilityResult.ok(new Opened(file, document.row()));
        } catch (IOException | RuntimeException failure) {
            return failed("Cannot open file", failure.getMessage());
        }
    }

    @Override public synchronized Optional<Path> currentFile() {
        ensureSession();
        return Optional.ofNullable(document()).map(EditorDocument::file);
    }

    @Override public synchronized Map<Path, List<DebugCapability.SourceBreakpoint>> sourceBreakpoints() {
        ensureSession();
        return breakpointSnapshotContents();
    }

    @Override public synchronized EditorBridge.SourceBreakpointSnapshot sourceBreakpointSnapshot() {
        ensureSession();
        return new EditorBridge.SourceBreakpointSnapshot(breakpointGeneration, breakpointSnapshotContents());
    }

    private Map<Path, List<DebugCapability.SourceBreakpoint>> breakpointSnapshotContents() {
        Map<Path, List<DebugCapability.SourceBreakpoint>> snapshot = new HashMap<>();
        breakpoints.forEach((file, points) -> snapshot.put(file, points.values().stream()
                .sorted(Comparator.comparingInt(DebugCapability.SourceBreakpoint::line)).toList()));
        return Map.copyOf(snapshot);
    }

    @Override public synchronized void updateSourceBreakpoints(
            Map<Path, List<DebugCapability.SourceBreakpoint>> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        ensureSession();
        applySourceBreakpointResults(results);
    }

    @Override public synchronized boolean updateSourceBreakpoints(long generation,
            Map<Path, List<DebugCapability.SourceBreakpoint>> results) {
        ensureSession();
        if (generation != breakpointGeneration) {
            return false;
        }
        applySourceBreakpointResults(results);
        return generation == breakpointGeneration;
    }

    private void applySourceBreakpointResults(Map<Path, List<DebugCapability.SourceBreakpoint>> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (Map.Entry<Path, List<DebugCapability.SourceBreakpoint>> source : results.entrySet()) {
            Map<Integer, DebugCapability.SourceBreakpoint> current = breakpoints.get(source.getKey());
            if (current == null) {
                continue;
            }
            Map<Integer, DebugCapability.SourceBreakpoint> byLine = new TreeMap<>();
            source.getValue().forEach(point -> byLine.put(point.line() - 1, point));
            if (!current.keySet().equals(byLine.keySet())) {
                continue;
            }
            breakpoints.put(source.getKey(), byLine);
        }
        persistBreakpoints();
    }

    public synchronized Optional<DocumentView> activeDocument() {
        ensureSession();
        EditorDocument document = document();
        return document == null ? Optional.empty() : Optional.of(new DocumentView(document.file().toString(), document.text(),
                document.row(), document.column(), document.dirty()));
    }

    public synchronized Optional<StatusBarInfo> statusBarInfo() {
        ensureSession();
        EditorDocument document = document();
        if (document == null) {
            return Optional.empty();
        }
        String lineSeparator = document.lineSeparator().equals("\r\n") ? "CRLF" : "LF";
        return Optional.of(new StatusBarInfo(document.row() + 1, document.column() + 1, "UTF-8", lineSeparator,
                EditorIndent.describe(document.text()), !Files.isWritable(document.file())));
    }

    public synchronized List<TabView> tabs() {
        ensureSession();
        List<TabView> result = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            EditorDocument document = documents.get(i);
            result.add(new TabView(project.relativize(document.file()).toString(), document.dirty(), i == active));
        }
        return List.copyOf(result);
    }

    public synchronized void vcsStatus(
            Map<String, VersionControlCapability.State> states,
            Map<String, Set<Integer>> changedLines) {
        vcsStates = Map.copyOf(states);
        vcsChangedLines = changedLines.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    synchronized Optional<VersionControlCapability.State> vcsState(String file) {
        return Optional.ofNullable(vcsStates.get(file.replace('\\', '/')));
    }

    synchronized boolean vcsChangedLine(int row) {
        EditorDocument document = document();
        if (document == null) { return false; }
        String file = project.relativize(document.file()).toString().replace('\\', '/');
        return vcsChangedLines.getOrDefault(file, Set.of()).contains(row);
    }

    public synchronized CapabilityResult<Boolean> save() {
        if (document() == null) { return failed("No file is open", ""); }
        try {
            Path file = document().file();
            Path root = project.toRealPath();
            if (!file.normalize().startsWith(root) || !file.getParent().toRealPath().startsWith(root)
                    || (Files.exists(file) && !file.toRealPath().startsWith(root))) {
                return failed("The file is outside the project", file.toString());
            }
            document().save();
            status = "Saved " + project.relativize(file);
            persist();
            return CapabilityResult.ok(true);
        } catch (IOException failure) { return failed("Cannot save file", failure.getMessage()); }
    }

    public synchronized boolean undo() { boolean changed = document() != null && document().undo(); if (changed) { changed(); } return changed; }
    public synchronized boolean redo() { boolean changed = document() != null && document().redo(); if (changed) { changed(); } return changed; }
    public synchronized void openFind() {
        ensureSession();
        EditorDocument document = document();
        if (document == null) {
            status = "No file is open";
            return;
        }
        element.openFind(document);
        focusRequest = TEXT_AREA_ID;
        status = "Find: type to search, Enter or F3 for next, Shift+Enter for previous, Escape closes";
    }
    public synchronized void closeFind() {
        element.closeFind();
        focusRequest = document() == null ? null : TEXT_AREA_ID;
        status = "Find closed";
    }
    public synchronized boolean findOpen() { return element.findOpenOn(document()); }
    public synchronized boolean findNext(String query) {
        boolean found = document() != null && document().find(query);
        status = found ? "Found: " + query : "No match: " + query;
        return found;
    }
    public synchronized void insert(String text) {
        if (document() == null) { return; }
        document().mutate(() -> document().state().insert(text.replace("\r\n", "\n")));
        changed();
    }
    /**
     * Keymap the editor reads its copy, cut and paste chords from. The shell binds its
     * keymap here; without a binding the default profile applies.
     */
    public void bindKeymap(Supplier<Keymap> bound) {
        keymap = bound;
    }

    Keymap keymap() {
        return keymap == null ? DEFAULT_KEYMAP : keymap.get();
    }

    public synchronized void copySelection() {
        EditorDocument document = document();
        if (document == null || !document.hasSelection()) {
            status = "No selection to copy";
            return;
        }
        status = clipboard.copy(document.selectedText());
    }

    public synchronized void cutSelection() {
        EditorDocument document = document();
        if (document == null || !document.hasSelection()) {
            status = "No selection to cut";
            return;
        }
        status = clipboard.copy(document.selectedText());
        document.replaceSelection("");
        persist();
    }

    public synchronized void pasteClipboard() {
        EditorDocument document = document();
        if (document == null) {
            status = "No file is open";
            return;
        }
        Optional<EditorClipboard.Paste> paste = clipboard.paste();
        if (paste.isEmpty() || paste.get().text().isEmpty()) {
            status = "Nothing to paste";
            return;
        }
        document.replaceSelection(paste.get().text().replace("\r\n", "\n").replace('\r', '\n'));
        persist();
        status = "Pasted from " + paste.get().origin();
    }

    public synchronized void duplicateLine() {
        EditorDocument document = document();
        if (document == null) {
            status = "No file is open";
            return;
        }
        document.duplicateLine();
        changed();
        status = "Line duplicated";
    }

    public synchronized boolean toggleImportFold() {
        EditorDocument document = document();
        boolean toggled = document != null && document.toggleImportFold();
        status = document == null ? "No file is open" : !toggled ? "No import block to fold"
                : document.importsFolded() ? "Imports folded" : "Imports expanded";
        return toggled;
    }
    public synchronized void nextTab() { selectTab(active + 1); }
    public synchronized void previousTab() { selectTab(active - 1); }
    public synchronized void selectTab(int index) {
        if (documents.isEmpty()) { return; }
        active = Math.floorMod(index, documents.size());
        cancelPointer();
        persist();
    }

    public synchronized CloseResult closeActive() {
        if (document() == null) { return CloseResult.EMPTY; }
        if (document().dirty()) {
            pendingClose = document().file();
            focusRequest = "editor-close-confirmation";
            status = "Discard unsaved changes to " + project.relativize(pendingClose) + "? [y/N]";
            return CloseResult.CONFIRM_REQUIRED;
        }
        removeActive();
        return CloseResult.CLOSED;
    }

    public synchronized boolean closeConfirmationPending() { return pendingClose != null; }
    public synchronized void confirmClose(boolean discard) {
        Path closing = pendingClose;
        pendingClose = null;
        if (!discard || closing == null) {
            status = "Close cancelled";
            focusRequest = document() == null ? null : TEXT_AREA_ID;
            return;
        }
        boolean found = false;
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).file().equals(closing)) { active = i; removeActive(); found = true; break; }
        }
        if (!found) {
            status = "Tab already closed";
            focusRequest = document() == null ? null : TEXT_AREA_ID;
        }
    }

    private void removeActive() {
        documents.remove(active);
        active = Math.min(active, documents.size() - 1);
        cancelPointer();
        focusRequest = document() == null ? null : TEXT_AREA_ID;
        status = "Tab closed";
        persist();
    }

    public synchronized void setDiagnostics(List<Diagnostic> diagnostics) {
        EditorDocument document = document();
        if (document != null) {
            document.diagnostics(diagnostics.stream().filter(value -> {
                Path path = value.filePath().isAbsolute() ? value.filePath() : project.resolve(value.filePath());
                try {
                    return path.toRealPath().equals(document.file());
                } catch (IOException unavailable) {
                    return path.toAbsolutePath().normalize().equals(document.file());
                }
            }).toList());
        }
    }

    public synchronized boolean jumpToDiagnostic(int index) {
        if (document() == null || index < 0 || index >= document().diagnostics().size()) { return false; }
        Diagnostic diagnostic = document().diagnostics().get(index);
        document().position(diagnostic.startLine(), diagnostic.startColumn());
        status = diagnostic.severity() + ": " + diagnostic.message();
        return true;
    }

    public synchronized List<Diagnostic> diagnostics() { return document() == null ? List.of() : document().diagnostics(); }

    synchronized void changed() {
        EditorDocument document = document();
        if (document != null) {
            Map<Integer, DebugCapability.SourceBreakpoint> source = breakpoints.get(document.file());
            if (source != null) {
                Set<Integer> previousLines = Set.copyOf(source.keySet());
                source.keySet().removeIf(line -> line >= document.lineCount());
                if (!previousLines.equals(source.keySet())) {
                    breakpointGeneration++;
                }
                syncBreakpoints(document.file());
            }
            persistBreakpoints();
        }
        persist();
    }

    public synchronized void restoreSession() { ensureSession(); }
    private void ensureSession() {
        if (sessionLoaded) { return; }
        sessionLoaded = true;
        if (sessionStore == null) { return; }
        try {
            if (breakpointStore != null) {
                for (BreakpointStore.Source source : breakpointStore.load(project)) {
                    Map<Integer, DebugCapability.SourceBreakpoint> restored = breakpoints.computeIfAbsent(source.file(),
                            ignored -> new TreeMap<>());
                    for (int line : source.lines()) {
                        restored.put(line, new DebugCapability.SourceBreakpoint(source.file().toString(), line + 1));
                    }
                }
                if (!breakpoints.isEmpty()) {
                    breakpointGeneration++;
                }
                for (Path source : List.copyOf(breakpoints.keySet())) {
                    syncBreakpoints(source);
                }
            }
            EditorSessionStore.Session session = sessionStore.load(project);
            for (EditorSessionStore.Tab tab : session.tabs()) {
                if (openFile(new OpenRequest(tab.file(), tab.row()), false).isFailure()) {
                    if (tab.recovery() == null) { continue; }
                    try {
                        Path root = project.toRealPath();
                        Path candidate = tab.file().isAbsolute() ? tab.file() : root.resolve(tab.file());
                        Path recoveryFile = candidate.normalize();
                        if (!recoveryFile.startsWith(root)) { continue; }
                        try {
                            if (Files.exists(recoveryFile) && !recoveryFile.toRealPath().startsWith(root)) { continue; }
                        } catch (IOException io) { continue; }
                        documents.add(new EditorDocument(recoveryFile, tab.recovery(), null));
                        active = documents.size() - 1;
                        status = "Recovered unsaved text for unavailable file " + project.relativize(recoveryFile);
                    } catch (IOException | RuntimeException ignored) { continue; }
                }
                if (tab.recovery() != null) { document().state().setText(tab.recovery()); }
                document().position(tab.row(), tab.column());
            }
            if (!documents.isEmpty()) { active = Math.clamp(session.selected(), 0, documents.size() - 1); }
            for (Path source : List.copyOf(breakpoints.keySet())) {
                syncBreakpoints(source);
            }
        } catch (IOException | RuntimeException failure) { status = "Cannot restore editor session: " + failure.getMessage(); }
    }

    @PreDestroy
    public synchronized void persist() {
        if (sessionStore == null || !sessionLoaded) { return; }
        try { sessionStore.save(project, documents, active); }
        catch (IOException failure) { status = "Cannot persist editor session: " + failure.getMessage(); }
    }

    private <T> CapabilityResult<T> failed(String reason, String detail) {
        status = reason + (detail == null || detail.isEmpty() ? "" : ": " + detail);
        return CapabilityResult.failed(new CapabilityFailure(reason, detail));
    }

    private static String projectKey(Path path) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean hasMixedLineEndings(String text) {
        boolean crlf = text.contains("\r\n");
        boolean bareLf = text.replace("\r\n", "").contains("\n");
        boolean bareCr = text.replace("\r\n", "").contains("\r");
        return bareCr || (crlf && bareLf);
    }
}
