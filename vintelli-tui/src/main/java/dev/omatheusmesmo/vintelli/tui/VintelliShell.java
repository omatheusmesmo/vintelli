package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellDiffViewPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellStateView;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import dev.omatheusmesmo.vintelli.tui.keymap.CommandPalette;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyAction;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapHelpOverlay;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapRegistry;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.menu.MenuEntry;
import dev.omatheusmesmo.vintelli.tui.menu.MenuPopup;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayoutStore;
import dev.omatheusmesmo.vintelli.tui.keymap.ConfigDirectory;
import dev.omatheusmesmo.vintelli.tui.search.ProjectSearchModel;
import dev.omatheusmesmo.vintelli.tui.search.ProjectSearchOverlay;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.elements.DockElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.RichTextElement;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.StackElement;
import dev.tamboui.toolkit.elements.TabsElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.GlobalEventHandler;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.style.Style;
import dev.tamboui.widgets.common.ScrollBarPolicy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.quarkus.arc.All;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * IntelliJ-like terminal shell: menu bar, tool window stripes (left, right, bottom),
 * project panel, editor panel and status bar. Runs at the Toolkit level: the runner gets the
 * style engine from {@link ShellThemes} and the element tree from
 * {@link #root()}, so every color resolves from the active theme stylesheet
 * and a runtime theme switch is one call away; shell code carries no hex
 * values. The shell is a {@link GlobalEventHandler}: the event router hands
 * it every key the focused panels do not consume first. It also implements
 * the write ports of {@code vintelli-api}: external surfaces such as the MCP
 * server push a {@link ShellHighlightTarget} and a {@link ShellUnifiedDiff}
 * and the editor panel paints them on the next frame, built from stylesheet
 * roles so the pushed content follows the theme too.
 *
 * <p>Every key is routed through the active {@link Keymap}: the shell matches
 * named actions instead of reading concrete keys, and the overlays (command
 * palette, keymap help) capture the keyboard while open. The reserved keymap
 * chords (palette, help, tool window activations) keep working while a
 * capturing input has focus, so a terminal-like tool window never locks the
 * user out of the shell. The keymap is resolved lazily, so a malformed
 * profile or user keymap file never aborts a headless or MCP-only process:
 * only the {@code --tui} session builds it, and fails there with a one line
 * message. The theme engine is resolved through the same laziness.
 *
 * <p>Tool windows declare their dock anchor via {@link ToolWindow#anchor()}.
 * The shell groups them into three stripes (left, right, bottom), each with
 * its own tab bar. The tab bar and the key routing are built from every {@link ToolWindow}
 * the {@link ToolWindowRegistry} exposes, so adding a tool window never
 * changes this class: the window declares itself, gets discovered as a CDI
 * bean and shows up in its anchor's stripe with a {@code tool-<id>} element id.
 * The project list is the single source of truth for the file selection: arrows,
 * page keys, Home/End and mouse clicks are consumed by the focused list
 * itself, so {@link #fileIndex()}, the {@code ShellStateSnapshot} read model
 * and the status line are read back from the list on every handled key and
 * every frame.
 *
 * <p>{@link #handle(Event)}, {@link #paint(Frame)} and the root element
 * handed to the runner share one lock: TamboUI widget and layout state is
 * mutated while a frame is drawn, so the TUI event loop and external threads
 * such as the inspect render endpoint must never draw or route keys
 * concurrently. The lock guards the real render path (the runner renders
 * {@link #root()} outside any runner-side serialization), the global key
 * handling and the key and mouse handling of the focused project panel; the
 * port writes stay lock-free volatile assignments.
 */
@Singleton
public final class VintelliShell implements GlobalEventHandler, ShellHighlightPort, ShellDiffViewPort {

    static final int TOOL_WINDOW_HEIGHT = 9;
    static final int PROJECT_PANEL_WIDTH = 30;
    static final int SIDE_PANEL_WIDTH = 20;

    private static final int MAX_EDITOR_DIFF_LINES = 6;
    private static final int MAX_EDITOR_LINE_LENGTH = 40;

    private static final List<String> MENUS = List.of("File", "Edit", "View", "Navigate", "Code", "Refactor", "Run", "Tools", "VCS", "Help");
    private static final List<String> PROJECT_FILES = List.of(
            "vintelli/pom.xml",
            "VintelliMain.java",
            "VintelliShell.java",
            "VintelliMcpTools.java",
            "VintelliShellTest.java"
    );
    private static final String EMPTY_TOOL_WINDOWS_TEXT = "no tool window beans registered; implement ToolWindow and expose the bean";
    private static final String EDITOR_TEXT = "editor tool window lands with issue #31; vintelli needs no external editor";

    /**
     * The keymap-driven parts of the shell, built together the first time the
     * TUI session actually uses them.
     */
    private record KeymapParts(Keymap keymap, CommandPalette commandPalette, KeymapHelpOverlay keymapHelp,
            MenuPopup menuPopup, ProjectSearchModel searchModel, ProjectSearchOverlay searchOverlay) {
    }

    private final ShellThemes themes;
    private final ShellState state;
    private final List<ToolWindow> toolWindows;
    private final BuiltinEditor editor;
    private final ProjectSearch projectSearch;
    private final List<NavigationCapability> navigationProviders;
    private final ShellLayout shellLayout;
    private final ShellLayoutStore layoutStore;
    private volatile DockElement layoutDock;
    private int nextDiagnosticIndex;
    private final Map<ToolWindow.Anchor, List<ToolWindow>> windowsByAnchor;
    private final ReentrantLock frameLock = new ReentrantLock();
    private final Supplier<Keymap> keymapSupplier;
    private final StackElement rootBase;
    private final Element lockedRoot;
    private final Element lockedProjectList;
    private final TabsElement toolTabs;
    private final ListElement<?> projectList;
    private Panel centerPanel;
    private volatile List<String> projectFiles;
    private volatile boolean projectFilesLoading;
    private volatile Runnable quitAction;
    private volatile KeymapParts overlays;

    private volatile ShellHighlightTarget highlight;
    private volatile ShellUnifiedDiff diff;
    private volatile String status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
    private volatile boolean inputCaptured;
    private volatile ToolWindow capturedWindow;
    private volatile int toolIndex;
    private volatile int syncedFileIndex = -1;

    // Active tool window index per anchor (for stripe tab selection)
    private final Map<ToolWindow.Anchor, Integer> activeToolIndexByAnchor = new ConcurrentHashMap<>();

    @Inject
    public VintelliShell(ToolWindowRegistry registry, ShellState state, KeymapRegistry keymapRegistry,
            Instance<ShellThemes> themes, ProjectSearch projectSearch,
            @All List<NavigationCapability> navigationProviders) {
        this(registry.toolWindows(), state, keymapRegistry::get, themes.get(), projectSearch, navigationProviders, true);
    }

    /**
     * Direct construction shell used by tests and the offscreen inspection
     * surface. The list must already be in deterministic tab order, so it
     * should come from a {@link ToolWindowRegistry}, and the default keymap
     * profile drives it.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes) {
        this(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(), false);
    }

    /**
     * Direct construction with one explicit keymap, for tests that assert a
     * named profile or a user keymap source.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Keymap keymap, ShellThemes themes) {
        this(toolWindows, state, () -> keymap, themes, null, List.of(), false);
    }

    /**
     * Lazy constructor: the keymap is built only when the shell first handles
     * a key or renders, so a headless or MCP-only process never builds it.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes) {
        this(toolWindows, state, keymapSupplier, themes, null, List.of(), false);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch) {
        this(toolWindows, state, keymapSupplier, themes, projectSearch, List.of(), false);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders) {
        this(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders, false);
    }

    private VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders,
            boolean persistLayout) {
        this.themes = themes;
        this.state = state;
        this.projectSearch = projectSearch;
        this.navigationProviders = List.copyOf(navigationProviders);
        this.editor = toolWindows.stream().filter(BuiltinEditor.class::isInstance)
                .map(BuiltinEditor.class::cast).findFirst().orElse(null);
        this.toolWindows = toolWindows.stream().filter(window -> !(window instanceof BuiltinEditor)).toList();
        this.projectFiles = projectSearch == null ? PROJECT_FILES : List.of();
        this.projectFilesLoading = projectSearch != null;
        Path projectRoot = editor == null ? Path.of("").toAbsolutePath().normalize() : editor.project();
        Path configRoot = persistLayout ? ConfigDirectory.configRoot(ConfigDirectory.sharedConfigDir(),
                System.getenv("XDG_CONFIG_HOME"), System.getenv("APPDATA"), System.getProperty("user.home")) : null;
        this.layoutStore = new ShellLayoutStore(configRoot, projectRoot);
        java.util.Set<String> allWindowIds = this.toolWindows.stream().map(ToolWindow::id).collect(java.util.stream.Collectors.toSet());
        this.shellLayout = layoutStore.load(allWindowIds);
        this.windowsByAnchor = this.toolWindows.stream()
                .collect(Collectors.groupingBy(ToolWindow::anchor, Collectors.toList()));
        this.keymapSupplier = keymapSupplier;
        if (!this.toolWindows.isEmpty()) {
            state.selectToolWindow(this.toolWindows.get(0).id());
            inputCaptured = this.toolWindows.get(0).capturesInput();
        }
        state.selectFile(this.projectFiles.isEmpty() ? "" : this.projectFiles.getFirst());
        syncedFileIndex = 0;
        this.toolTabs = toolWindowTabs();
        this.projectList = projectPanel();
        this.lockedProjectList = locked(projectList);
        this.rootBase = buildRootContent();
        this.lockedRoot = locked(rootBase);
        // Initialize active index for each anchor to first window
        for (ToolWindow.Anchor anchor : ToolWindow.Anchor.values()) {
            List<ToolWindow> windows = windowsByAnchor.getOrDefault(anchor, List.of());
            if (!windows.isEmpty()) {
                activeToolIndexByAnchor.put(anchor, 0);
            }
        }
        if (projectSearch != null) {
            Thread.ofVirtual().start(this::discoverProjectFiles);
        }
    }

    /**
     * The root of the element tree; the runner renders it on every frame, so
     * the returned element takes the frame lock around its render exactly
     * like {@link #handle(Event)} and {@link #paint(Frame)} do. The base
     * layout renders first, the overlays (command palette, keymap help)
     * render over the same area while they are open.
     */
    public Element root() {
        return lockedRoot;
    }

    private KeymapParts overlays() {
        KeymapParts parts = overlays;
        if (parts == null) {
            synchronized (this) {
                if (overlays == null) {
                    Keymap keymap = keymapSupplier.get();
                    ProjectSearchModel searchModel = projectSearch == null ? null : new ProjectSearchModel(projectSearch);
                    overlays = new KeymapParts(keymap, new CommandPalette(keymap.actions(), keymap),
                            new KeymapHelpOverlay(keymap), new MenuPopup(MENUS, this::menuEntries,
                                    keymap::describeBindings), searchModel,
                            searchModel == null ? null : new ProjectSearchOverlay(searchModel));
                }
                parts = overlays;
            }
        }
        return parts;
    }

    private StackElement buildRootContent() {
        StackElement stack = Toolkit.stack();
        stack.add(Toolkit.lazy(this::shellLayout));
        stack.add(Toolkit.lazy(this::paletteOverlayElement));
        stack.add(Toolkit.lazy(this::keymapHelpOverlayElement));
        stack.add(Toolkit.lazy(this::menuOverlayElement));
        stack.add(Toolkit.lazy(this::projectSearchOverlayElement));
        return stack;
    }

    private Element shellLayout() {
        boolean persisted = layoutStore.file() != null && Files.isRegularFile(layoutStore.file());
        Constraint projectWidth = persisted ? ratioConstraint(shellLayout.projectRatio())
                : Constraint.length(PROJECT_PANEL_WIDTH);
        Constraint bottomHeight = persisted ? ratioConstraint(shellLayout.bottomRatio())
                : Constraint.length(TOOL_WINDOW_HEIGHT + 2);
        DockElement dock = Toolkit.dock()
                .top(menuBar())
                .left(leftStripe())
                .center(centerPanel())
                .right(rightStripe())
                .bottom(bottomStripe())
                .topHeight(Constraint.length(1))
                .leftWidth(projectWidth)
                .rightWidth(Constraint.length(SIDE_PANEL_WIDTH))
                .bottomHeight(bottomHeight);
        dock.id("shell");
        layoutDock = dock;
        return dock;
    }

    private static Constraint ratioConstraint(double ratio) {
        int denominator = 10_000;
        return Constraint.ratio((int) Math.round(ratio * denominator), denominator);
    }

    private Element menuBar() {
        Row bar = Toolkit.row().id("menu-bar");
        bar.length(1);
        int activeMenu = overlays().menuPopup().isOpen() ? overlays().menuPopup().menuIndex() : -1;
        for (int index = 0; index < MENUS.size(); index++) {
            boolean selected = index == activeMenu;
            TextElement entry = Toolkit.text(selected ? " " + MENUS.get(index) + " " : MENUS.get(index));
            entry.addClass(selected ? "menu-entry-active" : "menu-entry");
            bar.add(entry);
        }
        return bar;
    }

    private Element leftStripe() {
        List<ToolWindow> leftWindows = visibleWindows(ToolWindow.Anchor.LEFT);
        if (leftWindows.isEmpty()) {
            // No left anchor tool windows: just the project panel
            return lockedProjectList;
        }
        // Stack: project panel on top, left tool window tabs and content below
        Column column = Toolkit.column().id("left-stripe");
        column.add(lockedProjectList);
        column.add(toolWindowStripe(ToolWindow.Anchor.LEFT));
        column.fill();
        return column;
    }

    private Element rightStripe() {
        List<ToolWindow> rightWindows = visibleWindows(ToolWindow.Anchor.RIGHT);
        if (rightWindows.isEmpty()) {
            return null;
        }
        Column column = Toolkit.column().id("right-stripe");
        column.add(toolWindowStripe(ToolWindow.Anchor.RIGHT));
        column.fill();
        return column;
    }

    private Element bottomStripe() {
        List<ToolWindow> bottomWindows = visibleWindows(ToolWindow.Anchor.BOTTOM);
        if (bottomWindows.isEmpty()) {
            TextElement empty = Toolkit.text(EMPTY_TOOL_WINDOWS_TEXT);
            empty.id("tool-windows-empty");
            empty.length(TOOL_WINDOW_HEIGHT + 1);
            return empty;
        }
        Column column = Toolkit.column().id("bottom-stripe");
        column.add(toolWindowStripe(ToolWindow.Anchor.BOTTOM));
        column.add(Toolkit.lazy(this::statusBarEntry));
        return column;
    }

    private Element toolWindowStripe(ToolWindow.Anchor anchor) {
        List<ToolWindow> windows = visibleWindows(anchor);
        if (windows.isEmpty()) {
            return Toolkit.text("").length(0);
        }

        // Create tab bar for this anchor
        TabsElement tabs = Toolkit.tabs(windows.stream()
                .map(w -> " " + w.shortcut() + ". " + w.title() + " ")
                .toList());
        tabs.id("tool-tabs-" + anchor.name().toLowerCase());
        tabs.length(1);
        tabs.divider(" ");
        tabs.padding("", "");
        int activeIdx = activeToolIndexByAnchor.getOrDefault(anchor, 0);
        List<ToolWindow> visibleForTab = visibleWindows(anchor);
        int clampedTab = visibleForTab.isEmpty() ? 0 : Math.min(activeIdx, visibleForTab.size() - 1);
        tabs.selected(clampedTab);

        // Column: tabs on top (fixed height 1), active tool window content below (fills remaining)
        Column column = Toolkit.column().id("tool-stripe-" + anchor.name().toLowerCase());
        column.add(tabs);
        column.add(Toolkit.lazy(() -> activeToolWindowElement(anchor)));
        column.fill();
        return column;
    }


    private List<ToolWindow> visibleWindows(ToolWindow.Anchor anchor) {
        return windowsByAnchor.getOrDefault(anchor, List.of()).stream()
                .filter(w -> shellLayout.isVisible(w.id()))
                .toList();
    }

    public boolean isToolWindowVisible(String id) {
        return shellLayout.isVisible(id);
    }

    public void toggleToolWindow(String id) {
        boolean wasVisible = shellLayout.isVisible(id);
        shellLayout.toggle(id);
        persistLayout();
        // If we just hid the active window of its anchor, move activation to the first visible sibling
        if (wasVisible && !shellLayout.isVisible(id)) {
            ToolWindow target = toolWindows.stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
            if (target != null) {
                ToolWindow.Anchor anchor = target.anchor();
                List<ToolWindow> remaining = visibleWindows(anchor);
                if (!remaining.isEmpty() && !remaining.contains(target)) {
                    int firstGlobal = toolWindows.indexOf(remaining.getFirst());
                    if (firstGlobal >= 0) {
                        switchToToolWindow(firstGlobal);
                        return;
                    }
                }
            }
        }
        status = shellLayout.isVisible(id) ? "Tool window shown: " + id : "Tool window hidden: " + id;
    }

    public void showToolWindow(String id) {
        shellLayout.setVisible(id, true);
        persistLayout();
        status = "Tool window shown: " + id;
    }

    public void hideToolWindow(String id) {
        shellLayout.setVisible(id, false);
        persistLayout();
        status = "Tool window hidden: " + id;
    }

    private Element activeToolWindowElement(ToolWindow.Anchor anchor) {
        List<ToolWindow> windows = visibleWindows(anchor);
        if (windows.isEmpty()) {
            return Toolkit.text("").length(0);
        }
        int index = activeToolIndexByAnchor.getOrDefault(anchor, 0);
        int clamped = Math.min(index, windows.size() - 1);
        if (clamped < 0) { clamped = 0; }
        return new ToolWindowElement(windows.get(clamped), anchor == ToolWindow.Anchor.BOTTOM ? TOOL_WINDOW_HEIGHT : -1);
    }

    private TabsElement toolWindowTabs() {
        // Backward compatibility: returns the bottom stripe tabs for legacy code
        List<ToolWindow> bottomWindows = windowsByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, List.of());
        TabsElement tabs = Toolkit.tabs(bottomWindows.stream()
                .map(window -> " " + window.shortcut() + ". " + window.title() + " ")
                .toList());
        tabs.id("tool-tabs");
        tabs.length(1);
        tabs.divider(" ");
        tabs.padding("", "");
        tabs.selected(activeToolIndexByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, 0));
        return tabs;
    }

    private List<String> tabTitles() {
        return toolWindows.stream()
                .map(window -> " " + window.shortcut() + ". " + window.title() + " ")
                .toList();
    }

    private ListElement<?> projectPanel() {
        ListElement<?> panel = Toolkit.list(projectFiles.isEmpty()
                ? List.of(projectFilesLoading ? "Loading project files..." : "No project files found")
                : projectFiles);
        panel.id("project-panel");
        panel.addClass("tool-panel");
        panel.title("Project");
        panel.focusable();
        panel.length(PROJECT_PANEL_WIDTH);
        panel.scrollbar(ScrollBarPolicy.AS_NEEDED);
        panel.selected(0);
        return panel;
    }

    private void discoverProjectFiles() {
        try {
            List<String> discovered = projectSearch.listFiles().stream().map(Path::toString).toList();
            frameLock.lock();
            try {
                projectFiles = discovered;
                projectFilesLoading = false;
                projectList.items(discovered.isEmpty() ? List.of("No project files found") : discovered);
                projectList.selectFirst();
                syncedFileIndex = projectList.selected();
                state.selectFile(discovered.isEmpty() ? "" : discovered.getFirst());
                status = discovered.isEmpty() ? "No project files found" : "Project files loaded";
            } finally {
                frameLock.unlock();
            }
        } catch (Exception unavailable) {
            frameLock.lock();
            try {
                projectFiles = List.of();
                projectFilesLoading = false;
                projectList.items(List.of("Project files unavailable"));
                state.selectFile("");
                status = "Project files unavailable: " + unavailable.getMessage();
            } finally {
                frameLock.unlock();
            }
        }
    }

    /**
     * A caller is whichever thread the runner or the event router drives: the
     * returned element delegates every render and input call to the wrapped
     * one under the frame lock, so the render pass, the key handling of the
     * focused list and its click handling cannot ever overlap an external
     * frame or an inspect render.
     */
    private Element locked(Element delegate) {
        return new LockedElement(delegate, frameLock);
    }

    private Panel centerPanel() {
        centerPanel = Toolkit.panel(Toolkit.lazy(this::editorContent))
                .title("Editor")
                .rounded()
                .id("editor-panel")
                .addClass("tool-panel")
                .focusable()
                .fill();
        return centerPanel;
    }

    private Element statusBarEntry() {
        syncFileSelection();
        TextElement statusBarText = Toolkit.text(" " + status);
        statusBarText.id("status-bar");
        statusBarText.length(1);
        return statusBarText;
    }

    private Element paletteOverlayElement() {
        KeymapParts parts = overlays();
        if (!parts.commandPalette().isOpen()) {
            return null;
        }
        return new OverlayElement("command-palette", parts.commandPalette()::render);
    }

    private Element keymapHelpOverlayElement() {
        KeymapParts parts = overlays();
        if (!parts.keymapHelp().isOpen()) {
            return null;
        }
        return new OverlayElement("keymap-help", parts.keymapHelp()::render);
    }

    private Element menuOverlayElement() {
        MenuPopup popup = overlays().menuPopup();
        if (!popup.isOpen()) {
            return null;
        }
        return new OverlayElement("menu-popup", popup::render);
    }

    private Element projectSearchOverlayElement() {
        KeymapParts parts = overlays();
        if (parts.searchModel() == null || !parts.searchModel().isOpen()) {
            return null;
        }
        return new OverlayElement("project-search", parts.searchOverlay()::render);
    }

    @Override
    public EventResult handle(Event event) {
        frameLock.lock();
        try {
            if (event instanceof MouseEvent mouse && mouse.isClick()) {
                return handleMouse(mouse);
            }
            return accept(event);
        } finally {
            frameLock.unlock();
        }
    }

    private EventResult handleMouse(MouseEvent mouse) {
        KeymapParts parts = overlays();
        if (mouse.y() == 0) {
            int menu = menuAt(mouse.x());
            if (menu >= 0) {
                parts.commandPalette().close();
                parts.keymapHelp().close();
                if (parts.searchModel() != null) {
                    parts.searchModel().close();
                }
                parts.menuPopup().open(menu);
                inputCaptured = false;
                return EventResult.HANDLED;
            }
        }
        if (parts.menuPopup().isOpen()) {
            parts.menuPopup().click(mouse.x(), mouse.y()).ifPresent(action -> dispatch(parts, action));
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private static int menuAt(int x) {
        int offset = 0;
        for (int index = 0; index < MENUS.size(); index++) {
            int width = MENUS.get(index).length() + 2;
            if (x >= offset && x < offset + width) {
                return index;
            }
            offset += width;
        }
        return -1;
    }

    private EventResult accept(Event event) {
        if (!(event instanceof KeyEvent key)) {
            return EventResult.UNHANDLED;
        }
        syncFileSelection();
        if (inputCaptured && key.isKey(KeyCode.ESCAPE)) {
            releaseCapture();
            return EventResult.HANDLED;
        }
        if (editor != null && editor.closeConfirmationPending()) {
            editor.handleKey(key);
            status = editor.status();
            return EventResult.HANDLED;
        }
        KeymapParts parts = overlays();
        if (!inputCaptured && parts.keymap().matches(key, KeyActions.QUIT)) {
            return EventResult.UNHANDLED;
        }
        if (parts.keymap().matchesReserved(key, KeyActions.SEARCH_EVERYWHERE)) {
            toggleCommandPalette(parts);
            return EventResult.HANDLED;
        }
        if (parts.keymap().matchesReserved(key, KeyActions.KEYMAP_HELP)) {
            toggleKeymapHelp(parts);
            return EventResult.HANDLED;
        }
        if (parts.commandPalette().isOpen()) {
            parts.commandPalette().handleKey(key).ifPresent(action -> dispatch(parts, action.id()));
            return EventResult.HANDLED;
        }
        if (parts.keymapHelp().isOpen()) {
            parts.keymapHelp().handleKey(key);
            return EventResult.HANDLED;
        }
        if (parts.menuPopup().isOpen()) {
            parts.menuPopup().handleKey(key).ifPresent(action -> dispatch(parts, action));
            return EventResult.HANDLED;
        }
        if (parts.searchModel() != null && parts.searchModel().isOpen()) {
            parts.searchOverlay().handleKey(key).ifPresent(target -> openFile(target.file(), target.line()));
            return EventResult.HANDLED;
        }
        if (parts.keymap().matchesReserved(key, KeyActions.MENU)) {
            parts.menuPopup().open(0);
            inputCaptured = false;
            return EventResult.HANDLED;
        }
        if (editor != null) {
            String editorAction = KeyActions.activateToolWindow(editor.id());
            boolean activateEditor = inputCaptured ? parts.keymap().matchesReserved(key, editorAction)
                    : parts.keymap().matches(key, editorAction);
            if (activateEditor) {
                editor.requestFocus();
                inputCaptured = false;
                status = "Editor focused";
                return EventResult.HANDLED;
            }
        }
        OptionalInt activation = activationByKey(parts, key);
        if (activation.isPresent()) {
            switchToToolWindow(activation.getAsInt());
            return EventResult.HANDLED;
        }
        for (KeyAction action : KeyActions.shellActions()) {
            if (KeyActions.QUIT.equals(action.id()) || KeyActions.MENU.equals(action.id())) {
                continue;
            }
            if (inputCaptured && KeyActions.OPEN_FILE.equals(action.id())) {
                continue;
            }
            boolean matched = inputCaptured ? parts.keymap().matchesReserved(key, action.id())
                    : parts.keymap().matches(key, action.id());
            if (matched) {
                dispatch(parts, action.id());
                return EventResult.HANDLED;
            }
        }
        if (inputCaptured) {
            ToolWindow capturing = capturedWindow;
            if (capturing == null) {
                // Fall back to the active bottom window if capture state is stale
                int bottomIndex = activeToolIndexByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, 0);
                List<ToolWindow> bottomWindows = windowsByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, List.of());
                if (bottomIndex >= 0 && bottomIndex < bottomWindows.size()) {
                    capturing = bottomWindows.get(bottomIndex);
                }
            }
            if (capturing != null) {
                capturing.handleKey(key);
            }
            return EventResult.HANDLED;
        }
        if (routeToToolWindow(key)) {
            return EventResult.HANDLED;
        }
        if (parts.keymap().matches(key, KeyActions.MOVE_UP)) {
            moveSelection(-1);
            return EventResult.HANDLED;
        }
        if (parts.keymap().matches(key, KeyActions.MOVE_DOWN)) {
            moveSelection(1);
            return EventResult.HANDLED;
        }
        if (key.isQuit()) {
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    /**
     * The keymap lanes the keymap runs the action through. The runner quits
     * itself: the keymap bindings are also the runner bindings, so an unhandled
     * quit chord stops the loop and a quit that the keymap does not bind is
     * swallowed here, matching a plain key press that only a capturing tool
     * window could have read.
     */
    /**
     * The palette dispatch runs a quit action when the catalog picks quit; the
     * host binds the runner quit here, because the shell itself holds no
     * runner reference. Quit chords bound by the keymap quit through the
     * built in binding set of the runner instead.
     */
    public void bindQuit(Runnable action) {
        quitAction = action;
    }

    private void dispatch(KeymapParts parts, String actionId) {
        switch (actionId) {
            case KeyActions.QUIT -> {
                closeOverlays(parts);
                Runnable quit = quitAction;
                if (quit != null) {
                    quit.run();
                }
            }
            case KeyActions.SEARCH_EVERYWHERE -> {
                parts.keymapHelp().close();
                parts.commandPalette().open();
                status = "Command palette: type to filter, Enter runs the action, Escape closes";
            }
            case KeyActions.KEYMAP_HELP -> {
                parts.commandPalette().close();
                openKeymapHelp(parts);
            }
            case KeyActions.MENU -> {
                parts.commandPalette().close();
                parts.keymapHelp().close();
                parts.menuPopup().open(0);
                inputCaptured = false;
            }
            case KeyActions.OPEN_FILE -> {
                closeOverlays(parts);
                openSelectedFile();
            }
            case KeyActions.SAVE -> {
                closeOverlays(parts);
                saveActiveFile();
            }
            case KeyActions.UNDO -> {
                closeOverlays(parts);
                editorUndo();
            }
            case KeyActions.REDO -> {
                closeOverlays(parts);
                editorRedo();
            }
            case KeyActions.FIND, KeyActions.FIND_IN_FILES -> {
                closeOverlays(parts);
                openSearch(parts, ProjectSearchModel.Mode.TEXT);
            }
            case KeyActions.GO_TO_FILE -> {
                closeOverlays(parts);
                openSearch(parts, ProjectSearchModel.Mode.FILES);
            }
            case KeyActions.CLOSE_TAB -> {
                closeOverlays(parts);
                closeEditorTab();
            }
            case KeyActions.NEXT_TAB -> {
                closeOverlays(parts);
                selectEditorTab(true);
            }
            case KeyActions.PREVIOUS_TAB -> {
                closeOverlays(parts);
                selectEditorTab(false);
            }
            case KeyActions.DEFINITION -> {
                closeOverlays(parts);
                navigateToSymbol(false);
            }
            case KeyActions.USAGES -> {
                closeOverlays(parts);
                navigateToSymbol(true);
            }
            case KeyActions.TOGGLE_IMPORT_FOLD -> {
                closeOverlays(parts);
                if (editor == null) {
                    status = "Built-in editor unavailable";
                } else {
                    editor.toggleImportFold();
                    status = editor.status();
                }
            }
            case KeyActions.NEXT_PROBLEM -> {
                closeOverlays(parts);
                nextProblem();
            }
            case KeyActions.PROJECT_WIDER -> {
                closeOverlays(parts);
                resizeProject(2);
            }
            case KeyActions.PROJECT_NARROWER -> {
                closeOverlays(parts);
                resizeProject(-2);
            }
            case KeyActions.BOTTOM_TALLER -> {
                closeOverlays(parts);
                resizeBottom(1);
            }
            case KeyActions.BOTTOM_SHORTER -> {
                closeOverlays(parts);
                resizeBottom(-1);
            }
            case KeyActions.MOVE_UP -> {
                closeOverlays(parts);
                moveSelection(-1);
            }
            case KeyActions.MOVE_DOWN -> {
                closeOverlays(parts);
                moveSelection(1);
            }
            default -> {
                if (actionId.startsWith("Toggle") && actionId.endsWith("ToolWindow")) {
                    for (ToolWindow tw : toolWindows) {
                        if (KeyActions.toggleToolWindow(tw.id()).equals(actionId)) {
                            toggleToolWindow(tw.id());
                            closeOverlays(parts);
                            return;
                        }
                    }
                }
                OptionalInt toolWindowIndex = indexOfActivation(actionId);
                closeOverlays(parts);
                if (editor != null && KeyActions.activateToolWindow(editor.id()).equals(actionId)) {
                    editor.requestFocus();
                    inputCaptured = false;
                    status = "Editor focused";
                } else if (toolWindowIndex.isEmpty()) {
                    status = "Unhandled action: " + actionId;
                } else {
                    switchToToolWindow(toolWindowIndex.getAsInt());
                }
            }
        }
    }

    private void openSelectedFile() {
        if (projectFiles.isEmpty()) {
            status = projectFilesLoading ? "Loading project files..." : "No project files found";
            return;
        }
        openFile(Path.of(projectFiles.get(projectList.selected())), 0);
    }

    private void openFile(Path file, int line) {
        if (editor == null) {
            status = "Built-in editor unavailable";
            return;
        }
        if (editor.open(new OpenRequest(file, line)).isFailure()) {
            status = editor.status();
            return;
        }
        status = editor.status();
    }

    private void saveActiveFile() {
        if (editor == null) {
            status = "Built-in editor unavailable";
            return;
        }
        editor.save();
        status = editor.status();
    }

    private void editorUndo() {
        if (editor == null || !editor.undo()) {
            status = editor == null ? "Built-in editor unavailable" : "Nothing to undo";
        } else {
            status = "Undo";
        }
    }

    private void editorRedo() {
        if (editor == null || !editor.redo()) {
            status = editor == null ? "Built-in editor unavailable" : "Nothing to redo";
        } else {
            status = "Redo";
        }
    }

    private void openSearch(KeymapParts parts, ProjectSearchModel.Mode mode) {
        if (parts.searchModel() == null) {
            status = "Project search unavailable";
            return;
        }
        parts.commandPalette().close();
        parts.keymapHelp().close();
        parts.menuPopup().close();
        inputCaptured = false;
        parts.searchModel().open(mode);
        status = mode == ProjectSearchModel.Mode.FILES ? "Go to File: type to filter" : "Find in Files: type to search";
    }

    private void closeEditorTab() {
        if (editor == null) {
            status = "Built-in editor unavailable";
            return;
        }
        BuiltinEditor.CloseResult result = editor.closeActive();
        status = result == BuiltinEditor.CloseResult.CONFIRM_REQUIRED ? editor.status()
                : result == BuiltinEditor.CloseResult.EMPTY ? "No editor tab is open" : "Tab closed";
    }

    private void selectEditorTab(boolean next) {
        if (editor == null || editor.tabs().isEmpty()) {
            status = "No editor tab is open";
            return;
        }
        if (next) {
            editor.nextTab();
        } else {
            editor.previousTab();
        }
        status = "Editor tab: " + editor.activeDocument().orElseThrow().file();
    }

    private void navigateToSymbol(boolean references) {
        if (editor == null || editor.activeDocument().isEmpty()) {
            status = "Open a project file first";
            return;
        }
        NavigationCapability provider = availableNavigationProvider();
        if (provider == null) {
            status = "Navigation provider unavailable";
            return;
        }
        BuiltinEditor.DocumentView document = editor.activeDocument().orElseThrow();
        NavigationCapability.SourcePosition position = new NavigationCapability.SourcePosition(
                Path.of(document.file()), document.line(), document.column());
        CapabilityResult<List<NavigationCapability.Location>> result = references
                ? provider.references(position, true) : provider.definition(position);
        if (result instanceof CapabilityResult.Ok<List<NavigationCapability.Location>> ok && !ok.value().isEmpty()) {
            NavigationCapability.Location location = ok.value().getFirst();
            openFile(location.filePath(), location.line());
        } else {
            status = result.failureOf().map(failure -> failure.reason() + ": " + failure.detail())
                    .orElse(references ? "No usages found" : "No definition found");
        }
    }

    private void nextProblem() {
        if (editor == null || editor.diagnostics().isEmpty()) {
            status = "No problems in the active file";
            return;
        }
        nextDiagnosticIndex = Math.floorMod(nextDiagnosticIndex, editor.diagnostics().size());
        editor.jumpToDiagnostic(nextDiagnosticIndex++);
        status = editor.status();
    }

    private void resizeProject(int delta) {
        int available = availableWidth();
        if (available <= 0 || layoutDock == null) {
            status = "Project layout is not ready";
            return;
        }
        shellLayout.projectWidth(projectList.renderedArea().width(), available);
        shellLayout.resizeProject(delta, available);
        layoutDock.leftWidth(Constraint.length(shellLayout.projectWidth(available)));
        persistLayout();
        status = "Project panel: " + shellLayout.projectWidth(available) + " columns";
    }

    private void resizeBottom(int delta) {
        int available = availableHeight();
        if (available <= 0 || layoutDock == null) {
            status = "Tool window layout is not ready";
            return;
        }
        shellLayout.bottomHeight(Math.max(0, available - centerPanel.renderedArea().height()), available);
        shellLayout.resizeBottom(delta, available);
        layoutDock.bottomHeight(Constraint.length(shellLayout.bottomHeight(available)));
        persistLayout();
        status = "Tool window height: " + shellLayout.bottomHeight(available) + " rows";
    }

    private int availableWidth() {
        Rect project = projectList.renderedArea();
        Rect center = centerPanel == null ? Rect.ZERO : centerPanel.renderedArea();
        return project.width() + center.width()
                + (windowsByAnchor.getOrDefault(ToolWindow.Anchor.RIGHT, List.of()).isEmpty() ? 0 : SIDE_PANEL_WIDTH);
    }

    private int availableHeight() {
        Rect center = centerPanel == null ? Rect.ZERO : centerPanel.renderedArea();
        return center.height() + TOOL_WINDOW_HEIGHT + 2;
    }

    private void persistLayout() {
        try {
            layoutStore.save(shellLayout);
        } catch (IOException failure) {
            status = "Cannot save layout: " + failure.getMessage();
        }
    }

    /**
     * The tool window whose activation chord the event triggers. Bare
     * shortcuts match only while the shell has the keyboard: once a capturing
     * window holds the input they belong to it; the reserved chord set is what
     * survives capture.
     */
    private OptionalInt activationByKey(KeymapParts parts, KeyEvent key) {
        Keymap keymap = parts.keymap();
        for (int index = 0; index < toolWindows.size(); index++) {
            String actionId = KeyActions.activateToolWindow(toolWindows.get(index).id());
            boolean triggered = inputCaptured
                    ? keymap.matchesReserved(key, actionId)
                    : keymap.matches(key, actionId);
            if (triggered) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    private OptionalInt indexOfActivation(String actionId) {
        for (int index = 0; index < toolWindows.size(); index++) {
            if (KeyActions.activateToolWindow(toolWindows.get(index).id()).equals(actionId)) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    private void toggleCommandPalette(KeymapParts parts) {
        inputCaptured = false;
        parts.keymapHelp().close();
        parts.menuPopup().close();
        if (parts.searchModel() != null) {
            parts.searchModel().close();
        }
        if (parts.commandPalette().isOpen()) {
            parts.commandPalette().close();
            status = "Command palette closed";
            return;
        }
        parts.commandPalette().open();
        status = "Command palette: type to filter, Enter runs the action, Escape closes";
    }

    private void toggleKeymapHelp(KeymapParts parts) {
        inputCaptured = false;
        parts.commandPalette().close();
        parts.menuPopup().close();
        if (parts.searchModel() != null) {
            parts.searchModel().close();
        }
        if (parts.keymapHelp().isOpen()) {
            parts.keymapHelp().close();
            status = "Keymap help closed";
            return;
        }
        openKeymapHelp(parts);
    }

    private void openKeymapHelp(KeymapParts parts) {
        parts.keymapHelp().open();
        status = "Keymap: " + parts.keymap().source();
    }

    private List<MenuEntry> menuEntries() {
        List<MenuEntry> entries = new ArrayList<>();
        boolean hasDocument = editor != null && editor.activeDocument().isPresent();
        boolean canEdit = hasDocument;
        String editorUnavailable = editor == null ? "Built-in editor unavailable"
                : hasDocument ? "" : "Open a project file first";
        addMenu(entries, "File", KeyActions.OPEN_FILE, "Open Selected File",
                editor == null ? "Built-in editor unavailable" : projectFiles.isEmpty() ? "No project files found" : "");
        addMenu(entries, "File", KeyActions.SAVE, "Save", canEdit ? "" : editorUnavailable);
        addMenu(entries, "File", KeyActions.CLOSE_TAB, "Close Editor Tab", canEdit ? "" : editorUnavailable);
        addMenu(entries, "File", KeyActions.QUIT, "Quit", "");
        addMenu(entries, "Edit", KeyActions.UNDO, "Undo", canEdit ? "" : editorUnavailable);
        addMenu(entries, "Edit", KeyActions.REDO, "Redo", canEdit ? "" : editorUnavailable);
        addMenu(entries, "Edit", KeyActions.FIND, "Find", canEdit ? "" : editorUnavailable);
        addMenu(entries, "View", KeyActions.PROJECT_WIDER, "Widen Project Panel", "");
        addMenu(entries, "View", KeyActions.PROJECT_NARROWER, "Narrow Project Panel", "");
        addMenu(entries, "View", KeyActions.BOTTOM_TALLER, "Grow Tool Windows", "");
        addMenu(entries, "View", KeyActions.BOTTOM_SHORTER, "Shrink Tool Windows", "");
        addMenu(entries, "View", KeyActions.TOGGLE_IMPORT_FOLD, "Fold or Unfold Imports", canEdit ? "" : editorUnavailable);
        addMenu(entries, "Navigate", KeyActions.GO_TO_FILE, "Go to File",
                projectSearch == null ? "Project search unavailable" : "");
        addMenu(entries, "Navigate", KeyActions.FIND_IN_FILES, "Find in Files",
                projectSearch == null ? "Project search unavailable" : "");
        String navigationUnavailable = availableNavigationProvider() == null
                ? navigationProviders.isEmpty() ? "Navigation provider unavailable"
                        : navigationProviders.stream().map(provider -> provider.health().reason())
                                .filter(Objects::nonNull).findFirst().orElse("Navigation provider unavailable")
                : "";
        addMenu(entries, "Navigate", KeyActions.DEFINITION, "Go to Definition",
                canEdit ? navigationUnavailable : editorUnavailable);
        addMenu(entries, "Navigate", KeyActions.USAGES, "Find Usages",
                canEdit ? navigationUnavailable : editorUnavailable);
        addMenu(entries, "Code", KeyActions.NEXT_PROBLEM, "Next Problem", canEdit ? "" : editorUnavailable);
        for (ToolWindow window : toolWindows) {
            entries.addAll(window.menuEntries());
        }
        return List.copyOf(entries);
    }

    private NavigationCapability availableNavigationProvider() {
        return navigationProviders.stream().filter(candidate -> candidate.health().isAvailable())
                .findFirst().orElse(null);
    }

    private static void addMenu(List<MenuEntry> entries, String menu, String action, String label, String reason) {
        entries.add(new MenuEntry(menu, action, label, reason));
    }


    /**
     * Closes the overlays; the keymap runs the closing at every non overlay
     * dispatch, so the shell returns to navigation with the next action.
     */
    private boolean closeOverlays(KeymapParts parts) {
        parts.commandPalette().close();
        parts.keymapHelp().close();
        parts.menuPopup().close();
        if (parts.searchModel() != null) {
            parts.searchModel().close();
        }
        return false;
    }

    private void switchToToolWindow(int globalIndex) {
        ToolWindow window = toolWindows.get(globalIndex);
        ToolWindow.Anchor anchor = window.anchor();

        // Make sure the target is visible before activating it
        if (!shellLayout.isVisible(window.id())) {
            showToolWindow(window.id());
        }

        // Find the index within the anchor's window list
        List<ToolWindow> anchorWindows = windowsByAnchor.getOrDefault(anchor, List.of());
        int anchorIndex = anchorWindows.indexOf(window);
        if (anchorIndex >= 0) {
            activeToolIndexByAnchor.put(anchor, anchorIndex);
        }

        // Update the global toolIndex for backward compatibility (bottom anchor)
        if (anchor == ToolWindow.Anchor.BOTTOM) {
            toolIndex = globalIndex;
        }

        inputCaptured = window.capturesInput();
        capturedWindow = inputCaptured ? window : null;
        toolTabs.selected(activeToolIndexByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, 0));
        state.selectToolWindow(window.id());
        status = "Tool window: " + window.title();
    }

    private boolean routeToToolWindow(KeyEvent key) {
        if (toolWindows.isEmpty()) {
            return false;
        }
        // Route to the active tool window of the bottom anchor (legacy behavior)
        int bottomIndex = activeToolIndexByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, 0);
        List<ToolWindow> bottomWindows = windowsByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, List.of());
        if (bottomWindows.isEmpty() || bottomIndex >= bottomWindows.size()) {
            return false;
        }
        ToolWindow active = bottomWindows.get(bottomIndex);
        if (active.capturesInput()) {
            return false;
        }
        return active.handleKey(key);
    }

    private void moveSelection(int delta) {
        if (projectFiles.isEmpty()) {
            status = projectFilesLoading ? "Loading project files..." : "No project files found";
            return;
        }
        int selected = projectList.selected();
        int target = Math.clamp(selected + delta, 0, projectFiles.size() - 1);
        if (target != selected) {
            projectList.selected(target);
            syncFileSelection();
        }
    }

    /**
     * Reads the selection back from the project list, the single source of
     * truth, and mirrors it into the {@code ShellStateSnapshot} read model
     * and the status line whenever it moved outside of this handler, which is
     * where the focused list consumes arrows, page keys, Home/End and clicks.
     */
    private void syncFileSelection() {
        if (projectFiles.isEmpty()) {
            syncedFileIndex = projectList.selected();
            state.selectFile("");
            // Only report the loading/empty state; do not overwrite an action status
            // that was set after the last frame (e.g. save, toggle).
            if (projectFilesLoading) {
                status = "Loading project files...";
            }
            return;
        }
        int selected = projectList.selected();
        if (selected == syncedFileIndex) {
            return;
        }
        syncedFileIndex = selected;
        state.selectFile(projectFiles.get(selected));
        status = "Selected: " + projectFiles.get(selected);
    }

    private void releaseCapture() {
        inputCaptured = false;
        capturedWindow = null;
        status = "Focus: shell navigation";
    }

    @Override
    public void highlight(ShellHighlightTarget target) {
        Objects.requireNonNull(target, "target");
        highlight = target;
    }

    @Override
    public void show(ShellUnifiedDiff unifiedDiff) {
        Objects.requireNonNull(unifiedDiff, "unifiedDiff");
        diff = unifiedDiff;
    }

    private Element editorContent() {
        ShellHighlightTarget pushed = highlight;
        ShellUnifiedDiff pushedDiff = diff;
        // Show pushed highlights/diffs even when a document is open
        if (pushed != null || pushedDiff != null) {
            List<Line> lines = new ArrayList<>();
            if (pushed != null) {
                lines.add(Line.styled("> highlight: " + pushed.file() + ":" + pushed.line(),
                        styledRole("editor-push-highlight")));
            }
            if (pushedDiff != null) {
                renderDiff(lines, pushedDiff);
            }
            RichTextElement content = Toolkit.richText(Text.from(lines));
            content.id("editor-content");
            content.addClass("editor-content");
            return content;
        }
        if (editor != null && editor.activeDocument().isPresent()) {
            return editor.element();
        }
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(EDITOR_TEXT));
        RichTextElement content = Toolkit.richText(Text.from(lines));
        content.id("editor-content");
        content.addClass("editor-content");
        return content;
    }

    private void renderDiff(List<Line> lines, ShellUnifiedDiff pushedDiff) {
        lines.add(Line.styled("diff: " + pushedDiff.title(), styledRole("editor-diff-title")));
        String[] split = pushedDiff.unifiedDiff().split("\n", -1);
        int end = split.length;
        if (end > 1 && split[end - 1].isEmpty()) {
            end--;
        }
        int shown = Math.min(end, MAX_EDITOR_DIFF_LINES);
        for (int index = 0; index < shown; index++) {
            lines.add(Line.styled(clip(split[index]), styledRole(diffRoleOf(split[index]))));
        }
        if (end > shown) {
            lines.add(Line.styled("... (" + (end - shown) + " more lines)", styledRole("editor-diff-marker")));
        }
    }

    private String diffRoleOf(String diffLine) {
        if (diffLine.startsWith("@@") || diffLine.startsWith("diff ")) {
            return "editor-diff-title";
        }
        if (diffLine.startsWith("+")) {
            return "editor-diff-added";
        }
        if (diffLine.startsWith("-")) {
            return "editor-diff-removed";
        }
        return "editor-diff-context";
    }

    private String clip(String diffLine) {
        if (diffLine.length() <= MAX_EDITOR_LINE_LENGTH) {
            return diffLine;
        }
        return diffLine.substring(0, MAX_EDITOR_LINE_LENGTH) + " ...";
    }

    private Style styledRole(String role) {
        return themes.resolver(role).toStyle();
    }

    /**
     * Draws one frame of the live shell into the frame buffer, under the same
     * lock that serializes key routing and the render of {@link #root()}.
     * Used by the inspect endpoint from external threads; it never mutates the
     * pushed state.
     */
    public void paint(Frame frame) {
        frameLock.lock();
        try {
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(themes.engine());
            lockedRoot.render(frame, frame.area(), context);
            context.registerElement(lockedRoot, frame.area());
        } finally {
            frameLock.unlock();
        }
    }

    /**
     * Activates another registered theme and reports its name, under the same
     * frame lock the renders use, so the next frame colors either side of the
     * swap cleanly. Unknown names fail with the available names in the
     * message; the named shell action (issue #144) and the gated inspect
     * surface are the callers.
     */
    public String switchTheme(String name) {
        frameLock.lock();
        try {
            themes.switchTheme(name);
            return themes.activeTheme();
        } finally {
            frameLock.unlock();
        }
    }

    public boolean paletteOpen() {
        return overlays().commandPalette().isOpen();
    }

    public boolean helpOverlayOpen() {
        return overlays().keymapHelp().isOpen();
    }

    /**
     * Resets the shell to its initial selection (first tool window, first
     * project file, ready status, closed overlays). Its only caller is the
     * web terminal integration test, which shares one shell bean between
     * test methods; production code drives the shell through key events and
     * never resets it.
     */
    public void reset() {
        if (!toolWindows.isEmpty()) {
            toolIndex = 0;
            inputCaptured = toolWindows.get(0).capturesInput();
            state.selectToolWindow(toolWindows.get(0).id());
        } else {
            toolIndex = 0;
            inputCaptured = false;
        }
        // Reset all anchor active indices to first window
        for (ToolWindow.Anchor anchor : ToolWindow.Anchor.values()) {
            List<ToolWindow> windows = windowsByAnchor.getOrDefault(anchor, List.of());
            if (!windows.isEmpty()) {
                activeToolIndexByAnchor.put(anchor, 0);
            }
        }
        state.selectFile(projectFiles.isEmpty() ? "" : projectFiles.getFirst());
        syncedFileIndex = 0;
        status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
        KeymapParts current = overlays;
        if (current != null) {
            current.commandPalette().close();
            current.keymapHelp().close();
            current.menuPopup().close();
            if (current.searchModel() != null) {
                current.searchModel().close();
            }
        }
    }

    public int toolIndex() {
        return activeToolIndexByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, 0);
    }

    public int fileIndex() {
        return projectList.selected();
    }

    public String status() {
        return status;
    }

    /**
     * Read model of this shell; see {@link ShellStateView} for the plain-type
     * contract other surfaces consume.
     */
    public ShellState state() {
        return state;
    }

    public String activeTheme() {
        return themes.activeTheme();
    }

    ShellThemes themes() {
        return themes;
    }

    /**
     * An overlay drawn over the whole shell area while it is open; the runner
     * renders it as the last stacked child, so it covers the base layout.
     */
    private record OverlayElement(String id, RenderCall render) implements Element {

        @Override
        public void render(Frame frame, Rect area, RenderContext context) {
            render.draw(frame, area);
        }

        @Override
        public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
            return Size.UNKNOWN;
        }

        @Override
        public String id() {
            return id;
        }
    }

    /**
     * A render or input call of a wrapped element run under the frame lock.
     * The runner routes key and mouse events to the registered element, so
     * wrapping the focused list is what puts its arrow and click handling
     * under the same guard the renders and the global handlers run in. The
     * passthrough of id, focus, constraint and area keeps registration, the
     * focus chain and the cascade identical to the unwrapped tree.
     */
    private record LockedElement(Element delegate, ReentrantLock lock) implements Element {

        @Override
        public void render(Frame frame, Rect area, RenderContext context) {
            lock.lock();
            try {
                delegate.render(frame, area, context);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
            return delegate.preferredSize(availableWidth, availableHeight, context);
        }

        @Override
        public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
            lock.lock();
            try {
                return delegate.handleKeyEvent(event, focused);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public EventResult handleMouseEvent(MouseEvent event) {
            lock.lock();
            try {
                return delegate.handleMouseEvent(event);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public boolean isFocusable() {
            return delegate.isFocusable();
        }

        @Override
        public Rect renderedArea() {
            return delegate.renderedArea();
        }

        @Override
        public Constraint constraint() {
            return delegate.constraint();
        }
    }

    @FunctionalInterface
    private interface RenderCall {
        void draw(Frame frame, Rect area);
    }
}
