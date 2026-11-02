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
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.search.ProjectSearchModel;
import dev.omatheusmesmo.vintelli.tui.search.ProjectSearchOverlay;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidgetContributor;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.arc.All;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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

    private enum VcsWriteAction { STAGE, UNSTAGE, COMMIT, SWITCH_BRANCH }

    private enum VcsPrompt { NONE, CONFIRM, COMMIT_MESSAGE, BRANCH_NAME }

    private final ShellThemes themes;
    private final ShellState state;
    private final List<ToolWindow> toolWindows;
    private final BuiltinEditor editor;
    private final ProjectSearch projectSearch;
    private final List<NavigationCapability> navigationProviders;
    private final CapabilityRegistry capabilityRegistry;
    private final List<StatusBarWidgetContributor> statusBarContributors;
    private final List<VersionControlCapability> vcsProviders;
    private final ProjectFileOperations fileOperations;
    private final ShellLayout shellLayout;
    private final ShellLayoutStore layoutStore;
    private volatile DockElement layoutDock;
    private int nextDiagnosticIndex;
    private final Map<ToolWindow.Anchor, List<ToolWindow>> windowsByAnchor;
    private final ReentrantLock frameLock = new ReentrantLock();
    private final AtomicLong vcsRefreshSequence = new AtomicLong();
    private VcsWriteAction pendingVcsAction;
    private VcsPrompt vcsPrompt = VcsPrompt.NONE;
    private Path pendingVcsFile;
    private String pendingVcsValue = "";
    private volatile boolean vcsWriteInProgress;
    private final Supplier<Keymap> keymapSupplier;
    private final StackElement rootBase;
    private final Element lockedRoot;
    private final Element lockedProjectList;
    private final TabsElement toolTabs;
    private final ListElement<?> projectList;
    private Panel centerPanel;
    private volatile List<String> projectFiles;
    private volatile Map<String, VersionControlCapability.State> vcsStates = Map.of();
    private volatile boolean projectFilesLoading;
    private final Path projectRoot;
    private final FileIcons fileIcons;
    private final boolean unicodeProjectTree;
    private final List<ProjectNode> rootNodes = new ArrayList<>();
    private volatile List<ProjectNode> visibleNodeObjects = List.of();
    private volatile boolean showHiddenFiles;
    private volatile String pendingDeletePath;
    private InputMode projectInputMode = InputMode.NONE;
    private final StringBuilder projectInput = new StringBuilder();
    private boolean pendingProjectFocus;
    private volatile Path copiedPath;
    private volatile Runnable quitAction;
    private volatile KeymapParts overlays;
    private final ThreadLocal<ActionSurface> actionSurface = ThreadLocal.withInitial(() -> ActionSurface.TUI);
    @Inject
    private UiWorkQueue uiWorkQueue;
    @ConfigProperty(name = "vintelli.tui.allowed-actions", defaultValue = "*")
    private String tuiAllowedActions = "*";
    @ConfigProperty(name = "vintelli.web.allowed-actions", defaultValue = "none")
    private String webAllowedActions = "none";
    @ConfigProperty(name = "vintelli.mcp.allowed-actions", defaultValue = "none")
    private String mcpAllowedActions = "none";

    private volatile ShellHighlightTarget highlight;
    private volatile ShellUnifiedDiff diff;
    private volatile String status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
    private volatile boolean inputCaptured;
    private volatile ToolWindow capturedWindow;
    private volatile String focusedToolWindowId;
    private volatile int toolIndex;
    private volatile int syncedFileIndex = -1;

    // Active tool window index per anchor (for stripe tab selection)
    private final Map<ToolWindow.Anchor, Integer> activeToolIndexByAnchor = new ConcurrentHashMap<>();

    @Inject
    public VintelliShell(ToolWindowRegistry registry, ShellState state, KeymapRegistry keymapRegistry,
            Instance<ShellThemes> themes, ProjectSearch projectSearch,
            @All List<NavigationCapability> navigationProviders, CapabilityRegistry capabilityRegistry,
            @All List<StatusBarWidgetContributor> statusBarContributors,
            @All List<VersionControlCapability> vcsProviders, @All List<ProjectFileOperations> fileOperations) {
        this(registry.toolWindows(), state, keymapRegistry::get, themes.get(), projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, true, statusBarContributors,
                fileOperations.isEmpty() ? null : fileOperations.getFirst());
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes) {
        this(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(), null, List.of(),
                false, toolWindows, null);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes,
            CapabilityRegistry capabilityRegistry) {
        this(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(), capabilityRegistry,
                List.of(), false, toolWindows, null);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Keymap keymap, ShellThemes themes) {
        this(toolWindows, state, () -> keymap, themes, null, List.of(), null, List.of(), false, toolWindows, null);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes) {
        this(toolWindows, state, keymapSupplier, themes, null, List.of(), null, List.of(), false, toolWindows, null);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch) {
        this(toolWindows, state, keymapSupplier, themes, projectSearch, List.of(), null, List.of(), false, toolWindows,
                null);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders,
            CapabilityRegistry capabilityRegistry) {
        this(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders, capabilityRegistry,
                List.of(), false, toolWindows, null);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders,
            List<VersionControlCapability> vcsProviders) {
        this(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders, null, vcsProviders, false,
                toolWindows, null);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, ProjectFileOperations fileOperations) {
        this(toolWindows, state, keymapSupplier, themes, projectSearch, List.of(), null, List.of(), false, toolWindows,
                fileOperations);
    }

    private VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders,
            CapabilityRegistry capabilityRegistry, List<VersionControlCapability> vcsProviders,
            boolean persistLayout, List<? extends StatusBarWidgetContributor> statusBarContributors,
            ProjectFileOperations fileOperations) {
        this.themes = themes;
        this.state = state;
        this.projectSearch = projectSearch;
        this.fileOperations = fileOperations;
        this.navigationProviders = List.copyOf(navigationProviders);
        this.capabilityRegistry = capabilityRegistry;
        this.statusBarContributors = List.copyOf(statusBarContributors);
        this.vcsProviders = List.copyOf(vcsProviders);
        this.editor = toolWindows.stream().filter(BuiltinEditor.class::isInstance)
                .map(BuiltinEditor.class::cast).findFirst().orElse(null);
        this.toolWindows = toolWindows.stream().filter(window -> !(window instanceof BuiltinEditor)).toList();
        this.toolWindows.stream().filter(StructureToolWindow.class::isInstance)
                .map(StructureToolWindow.class::cast)
                .forEach(window -> window.setNavigateHandler(symbol -> {
                    if (editor != null) {
                        editor.activeDocument().ifPresent(document -> {
                            releaseCapture();
                            openFile(Path.of(document.file()), symbol.selectionStartLine());
                        });
                    }
                }));
        this.toolWindows.stream().filter(ProblemsToolWindow.class::isInstance)
                .map(ProblemsToolWindow.class::cast)
                .forEach(window -> window.setNavigateHandler(problem -> {
                    if (editor == null) {
                        status = "Built-in editor unavailable";
                        return;
                    }
                    try {
                        Path root = editor.project().toRealPath();
                        Path file = problem.filePath().isAbsolute() ? problem.filePath() : root.resolve(problem.filePath());
                        file = file.toRealPath();
                        if (!file.startsWith(root)) {
                            status = "Diagnostic file is outside the project";
                            return;
                        }
                        selectPath(root.relativize(file).toString().replace('\\', '/'));
                        releaseCapture();
                        openFile(file, problem.startLine());
                    } catch (IOException unavailable) {
                        status = "Diagnostic file is unavailable";
                    }
                }));
        this.projectFiles = projectSearch == null ? PROJECT_FILES : List.of();
        this.projectFilesLoading = projectSearch != null;
        Path resolvedRoot = editor == null ? Path.of("").toAbsolutePath().normalize() : editor.project();
        try {
            if (Files.exists(resolvedRoot)) {
                resolvedRoot = resolvedRoot.toRealPath();
            }
        } catch (IOException ignored) {
        }
        this.projectRoot = resolvedRoot.normalize();
        this.fileIcons = FileIcons.resolve();
        this.unicodeProjectTree = ProjectTreeGlyphs.supportsUnicode();
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
        this.lockedProjectList = new LockedElement(projectList, frameLock, this);
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
        if (uiWorkQueue != null) {
            uiWorkQueue.drain();
        }
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
        return new ToolWindowElement(windows.get(clamped), anchor == ToolWindow.Anchor.BOTTOM ? TOOL_WINDOW_HEIGHT : -1,
                this);
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
                : projectFileRows());
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
        frameLock.lock();
        try {
            if (projectFilesLoading) {
                refreshProjectTree();
            }
        } catch (Exception unavailable) {
            projectFiles = List.of();
            projectFilesLoading = false;
            visibleNodeObjects = List.of();
            rootNodes.clear();
            projectList.items(List.of("Project files unavailable"));
            state.selectFile("");
            status = "Project files unavailable: " + unavailable.getMessage();
        } finally {
            frameLock.unlock();
        }
    }

    public void refreshProjectTree() {
        refreshProjectTree(true);
    }

    public void refresh() {
        refreshProjectTree();
    }

    public void refreshProject() {
        refreshProjectTree();
    }

    public void reloadProjectTree() {
        refreshProjectTree();
    }

    private void refreshProjectTree(boolean updateStatus) {
        frameLock.lock();
        try {
            if (projectSearch == null) {
                return;
            }
            List<Path> files = projectSearch.listFiles();
            List<Path> directories = projectSearch.listDirectories();
            Set<Path> directoryPaths = new HashSet<>(directories);
            List<Path> entries = new ArrayList<>(directories);
            entries.addAll(files);
            List<Path> filtered = showHiddenFiles ? entries
                    : entries.stream().filter(path -> {
                        for (int i = 0; i < path.getNameCount(); i++) {
                            if (path.getName(i).toString().startsWith(".")) {
                                return false;
                            }
                        }
                        return true;
                    }).toList();
            buildProjectTree(filtered, directoryPaths);
            String previous = (projectFiles == null || projectFiles.isEmpty()) ? ""
                    : (projectList.selected() >= 0 && projectList.selected() < projectFiles.size()
                            ? projectFiles.get(projectList.selected()) : "");
            recomputeVisible(previous);
            projectFilesLoading = false;
            if (updateStatus) {
                status = visibleNodeObjects.isEmpty() ? "No project files found" : "Project files loaded";
            }
        } finally {
            frameLock.unlock();
        }
    }

    private void buildProjectTree(List<Path> files, Set<Path> directoryPaths) {
        Set<Path> expanded = new HashSet<>();
        collectExpanded(rootNodes, expanded);
        List<ProjectNode> newRoots = new ArrayList<>();
        Map<Path, ProjectNode> byPath = new HashMap<>();
        for (Path file : files) {
            ProjectNode parent = null;
            Path accumulated = Path.of("");
            for (int i = 0; i < file.getNameCount(); i++) {
                accumulated = accumulated.resolve(file.getName(i));
                boolean leaf = i == file.getNameCount() - 1;
                ProjectNode node = byPath.get(accumulated);
                if (node == null) {
                    node = new ProjectNode(accumulated, file.getName(i).toString(), !leaf || directoryPaths.contains(accumulated));
                    node.expanded = expanded.contains(accumulated);
                    node.parent = parent;
                    node.children = new ArrayList<>();
                    byPath.put(accumulated, node);
                    if (parent == null) {
                        newRoots.add(node);
                    } else {
                        parent.children.add(node);
                    }
                } else if (!leaf) {
                    node.directory = true;
                }
                parent = node;
            }
        }
        sortProjectNodes(newRoots);
        rootNodes.clear();
        rootNodes.addAll(newRoots);
    }

    private static void collectExpanded(List<ProjectNode> nodes, Set<Path> expanded) {
        for (ProjectNode node : nodes) {
            if (node.expanded) {
                expanded.add(node.relative);
            }
            collectExpanded(node.children, expanded);
        }
    }

    private void sortProjectNodes(List<ProjectNode> nodes) {
        if (nodes == null || nodes.size() < 2) {
            if (nodes != null) {
                for (ProjectNode node : new ArrayList<>(nodes)) {
                    if (node != null && node.children != null && !node.children.isEmpty()
                            && node.children != nodes) {
                        sortProjectNodes(node.children);
                    }
                }
            }
            return;
        }
        List<ProjectNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing((ProjectNode node) -> !node.directory)
                .thenComparing(node -> node.name));
        nodes.clear();
        nodes.addAll(sorted);
        for (ProjectNode node : new ArrayList<>(nodes)) {
            if (node != null && node.children != null && !node.children.isEmpty()
                    && node.children != nodes) {
                sortProjectNodes(node.children);
            }
        }
    }

    private void recomputeVisible(String previousSelection) {
        List<ProjectNode> objects = new ArrayList<>();
        appendVisible(rootNodes, objects);
        visibleNodeObjects = List.copyOf(objects);
        List<String> paths = new ArrayList<>();
        for (ProjectNode node : objects) {
            paths.add(node.relative.toString().replace('\\', '/'));
        }
        projectFiles = List.copyOf(paths);
        List<String> labels = new ArrayList<>();
        for (ProjectNode node : objects) {
            labels.add(projectNodeLabel(node));
        }
        projectList.items(labels.isEmpty() ? List.of("No project files found") : labels);
        int index = paths.indexOf(previousSelection);
        if (index < 0 && !paths.isEmpty()) {
            index = 0;
        }
        if (index >= 0) {
            projectList.selected(index);
        } else {
            projectList.selected(0);
        }
        syncedFileIndex = projectList.selected();
        if (paths.isEmpty()) {
            state.selectFile("");
        } else {
            int selected = projectList.selected();
            if (selected >= 0 && selected < paths.size()) {
                state.selectFile(paths.get(selected));
            }
        }
    }

    private String projectNodeLabel(ProjectNode node) {
        boolean nerd = fileIcons.mode() == IconMode.NERD;
        List<ProjectNode> ancestors = new ArrayList<>();
        for (ProjectNode parent = node.parent; parent != null; parent = parent.parent) {
            ancestors.add(parent);
        }
        StringBuilder label = new StringBuilder();
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            label.append(isLastSibling(ancestors.get(i)) ? "    " : unicodeProjectTree ? "│   " : "|   ");
        }
        label.append(isLastSibling(node) ? (unicodeProjectTree ? "└── " : "\\-- ")
                : (unicodeProjectTree ? "├── " : "|-- "));
        if (node.directory) {
            label.append(nerd ? (node.expanded ? "▾ " : "▸ ") : (node.expanded ? "- " : "+ "));
        }
        label.append((node.directory ? fileIcons.folderIconFor(node.name) : fileIcons.iconFor(node.name))
                .symbol(fileIcons.mode())).append(' ');
        label.append(node.name);
        if (node.directory) {
            label.append('/');
        }
        return label.toString();
    }

    private boolean isLastSibling(ProjectNode node) {
        List<ProjectNode> siblings = node.parent == null ? rootNodes : node.parent.children;
        return !siblings.isEmpty() && siblings.getLast() == node;
    }

    private void appendVisible(List<ProjectNode> nodes, List<ProjectNode> output) {
        for (ProjectNode node : nodes) {
            output.add(node);
            if (node.directory && node.expanded && node.children != null) {
                appendVisible(node.children, output);
            }
        }
        refreshVcs();
    }

    /**
     * A caller is whichever thread the runner or the event router drives: the
     * returned element delegates every render and input call to the wrapped
     * one under the frame lock, so the render pass, the key handling of the
     * focused list and its click handling cannot ever overlap an external
     * frame or an inspect render.
     */
    private Element locked(Element delegate) {
        return new LockedElement(delegate, frameLock, null);
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
        return new StatusBarElement(this::statusBarModelWidgets);
    }

    private List<StatusBarWidget> statusBarModelWidgets() {
        List<StatusBarWidget> widgets = new ArrayList<>();
        String shellMessage = status.replace(" | IDEA MCP: disconnected", "");
        widgets.add(new StatusBarWidget("shell", 0, shellMessage, compactShellStatus(shellMessage)));
        if (projectInputMode != InputMode.NONE || vcsPrompt != VcsPrompt.NONE || pendingDeletePath != null) {
            return List.copyOf(widgets);
        }
        if (capabilityRegistry != null) {
            int order = 10;
            for (CapabilityStatusWidgets capabilityStatusWidgets : capabilityRegistry.statusBarSnapshot()) {
                CapabilityStatus capabilityStatus = capabilityStatusWidgets.status();
                widgets.add(capabilityWidget(capabilityStatus, order++));
                widgets.addAll(capabilityStatusWidgets.widgets());
            }
        }
        if (editor != null) {
            editor.statusBarInfo().ifPresent(info -> {
                String cursor = "L" + info.line() + ":C" + info.column();
                widgets.add(new StatusBarWidget("editor-caret", 8, "Caret " + cursor, cursor));
                String details = info.encoding() + " · " + info.lineSeparator() + " · " + info.fileType();
                widgets.add(new StatusBarWidget("editor-format", 101, details,
                        info.encoding().replace("-", "") + " " + info.lineSeparator() + " " + info.fileType()));
            });
        }
        for (StatusBarWidgetContributor contributor : statusBarContributors) {
            widgets.addAll(contributor.statusBarWidgets());
        }
        return List.copyOf(widgets);
    }

    private StatusBarWidget capabilityWidget(CapabilityStatus status, int order) {
        String label = switch (status.capability()) {
            case RUN -> "Run";
            case TERMINAL -> "Terminal";
            case READ_FILE -> "Read File";
            case DIAGNOSTICS -> "Problems";
            case CALL_HIERARCHY -> "Call Hierarchy";
            case EDITOR_BRIDGE -> "Editor";
            case VERSION_CONTROL -> "VCS";
        };
        String shortLabel = switch (status.capability()) {
            case RUN -> "R";
            case TERMINAL -> "T";
            case READ_FILE -> "RF";
            case DIAGNOSTICS -> "P";
            case CALL_HIERARCHY -> "CH";
            case EDITOR_BRIDGE -> "E";
            case VERSION_CONTROL -> "VCS";
        };
        String id = "capability-" + status.capability().label();
        if (status.isAvailable()) {
            return new StatusBarWidget(id, order,
                    label + ": " + status.providerIdentifier() + " (" + status.provenance() + ")",
                    shortLabel + ":" + StatusBarLayout.truncate(status.providerIdentifier(), 7));
        }
        return new StatusBarWidget(id, order, label + ": unavailable: " + status.health().reason(),
                shortLabel + ":!" + compactReason(status.health().reason()));
    }

    private static String compactShellStatus(String message) {
        if (message.startsWith("Tool window: ")) {
            return message;
        }
        if (message.startsWith("Vintelli ready") || message.equals("Project files loaded")) {
            return "Ready";
        }
        if (message.startsWith("Loading project files")) {
            return "Loading";
        }
        if (message.startsWith("No project files")) {
            return "No files";
        }
        if (message.startsWith("Project files unavailable")) {
            return "Project!";
        }
        return StatusBarLayout.truncate(message, 8);
    }

    private static String compactReason(String reason) {
        return StatusBarLayout.truncate(reason, 12);
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
        return handle(event, ActionSurface.TUI);
    }

    /** Routes input with the allow-list of its originating surface. */
    public EventResult handle(Event event, ActionSurface surface) {
        frameLock.lock();
        ActionSurface previous = actionSurface.get();
        actionSurface.set(surface == null ? ActionSurface.TUI : surface);
        try {
            if (event instanceof MouseEvent mouse && (mouse.isClick() || mouse.isScroll())) {
                return handleMouse(mouse);
            }
            return accept(event);
        } finally {
            actionSurface.set(previous);
            frameLock.unlock();
        }
    }

    private EventResult handleMouse(MouseEvent mouse) {
        KeymapParts parts = overlays();
        if (mouse.isClick() && mouse.y() == 0) {
            int menu = menuAt(mouse.x());
            if (menu >= 0) {
                parts.commandPalette().close();
                parts.keymapHelp().close();
                if (parts.searchModel() != null) {
                    parts.searchModel().close();
                }
                openMenu(parts, menu);
                inputCaptured = false;
                return EventResult.HANDLED;
            }
        }
        if (parts.menuPopup().isOpen()) {
            if (mouse.isClick()) {
                Optional<String> action = parts.menuPopup().click(mouse.x(), mouse.y());
                syncEditorMenuNavigation(parts);
                action.ifPresent(value -> dispatch(parts, value));
            }
            return EventResult.HANDLED;
        }
        ToolWindow activeWindow = activeBottomWindow();
        if (activeWindow != null) {
            String action = activeWindow.actionForMouse(mouse);
            if (action != null) {
                focusToolWindow(activeWindow);
                dispatch(parts, action);
                return EventResult.HANDLED;
            }
            if (activeWindow.handleMouse(mouse)) {
                focusToolWindow(activeWindow);
                return EventResult.HANDLED;
            }
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
        if (projectInputMode != InputMode.NONE && handleProjectKey(key)) {
            return EventResult.HANDLED;
        }
        if (pendingDeletePath != null) {
            if (key.isCharIgnoreCase('y')) {
                deleteSelectedConfirmed();
                return EventResult.HANDLED;
            }
            if (key.isCharIgnoreCase('n') || key.isKey(KeyCode.ESCAPE)) {
                cancelDelete();
                return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }
        if (inputCaptured && key.isKey(KeyCode.ESCAPE)) {
            releaseCapture();
            return EventResult.HANDLED;
        }
        if (editor != null && editor.closeConfirmationPending()) {
            editor.handleKey(key);
            status = editor.status();
            return EventResult.HANDLED;
        }
        if (vcsPrompt != VcsPrompt.NONE) {
            handleVcsPromptKey(key);
            return EventResult.HANDLED;
        }
        if (vcsWriteInProgress) {
            status = "Git operation in progress";
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
            Optional<String> action = parts.menuPopup().handleKey(key);
            syncEditorMenuNavigation(parts);
            action.ifPresent(value -> dispatch(parts, value));
            return EventResult.HANDLED;
        }
        if (parts.searchModel() != null && parts.searchModel().isOpen()) {
            parts.searchOverlay().handleKey(key).ifPresent(target -> openFile(target.file(), target.line()));
            return EventResult.HANDLED;
        }
        if (parts.keymap().matchesReserved(key, KeyActions.MENU)) {
            openMenu(parts, 0);
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
        if (!inputCaptured) {
            ToolWindow active = activeBottomWindow();
            if (active != null && active.id().equals(focusedToolWindowId)
                    && active.shouldHandleKeyBeforeShell(key)) {
                return handleToolWindowKey(parts, active, key) ? EventResult.HANDLED : EventResult.UNHANDLED;
            }
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
                handleToolWindowKey(parts, capturing, key);
            }
            return EventResult.HANDLED;
        }
        // Give the active non-capturing tool window the same first refusal it
        // gets after mouse focus. In particular this preserves legacy tool
        // windows that consume ordinary keys without declaring named actions.
        // Run is intentionally focus-scoped because its j/k bindings would
        // otherwise shadow project navigation while the editor owns focus.
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
        if (key.isRight()) {
            expandSelected();
            return EventResult.HANDLED;
        }
        if (key.isLeft()) {
            collapseSelected();
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
        dispatch(parts, actionId, null);
    }

    private void dispatch(KeymapParts parts, String actionId, KeyEvent sourceKey) {
        if (isCommandAction(actionId) && !actionAllowed(actionSurface.get(), actionId)) {
            closeOverlays(parts);
            status = "Action disabled for " + actionSurface.get().name().toLowerCase() + " surface: " + actionId;
            return;
        }
        for (ToolWindow window : toolWindows) {
            if (window.performAction(actionId, sourceKey)) {
                closeOverlays(parts);
                return;
            }
        }
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
                openMenu(parts, 0);
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
            case KeyActions.VCS_REFRESH -> {
                closeOverlays(parts);
                refreshVcsAsync();
            }
            case KeyActions.VCS_SHOW_DIFF -> {
                closeOverlays(parts);
                showVcsDiff();
            }
            case KeyActions.VCS_DIFF_WORKING_TREE -> {
                closeOverlays(parts);
                showVcsWorkingTreeDiff();
            }
            case KeyActions.VCS_STAGE -> {
                closeOverlays(parts);
                requestStage(false);
            }
            case KeyActions.VCS_UNSTAGE -> {
                closeOverlays(parts);
                requestStage(true);
            }
            case KeyActions.VCS_COMMIT -> {
                closeOverlays(parts);
                requestCommit();
            }
            case KeyActions.VCS_SWITCH_BRANCH -> {
                closeOverlays(parts);
                requestBranchSwitch();
            }
            case KeyActions.PROJECT_NEW_FILE -> {
                closeOverlays(parts);
                beginProjectInput(InputMode.CREATE_FILE);
            }
            case KeyActions.PROJECT_NEW_DIRECTORY -> {
                closeOverlays(parts);
                beginProjectInput(InputMode.CREATE_DIRECTORY);
            }
            case KeyActions.PROJECT_RENAME -> {
                closeOverlays(parts);
                beginProjectInput(InputMode.RENAME);
            }
            case KeyActions.PROJECT_DELETE -> {
                closeOverlays(parts);
                requestDeleteSelected();
            }
            case KeyActions.PROJECT_REFRESH -> {
                closeOverlays(parts);
                refreshProjectTree();
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
            case KeyActions.SWITCH_THEME -> {
                closeOverlays(parts);
                List<String> themeNames = themes.availableThemeNames();
                int themeIndex = themeNames.indexOf(themes.activeTheme());
                themes.switchTheme(themeNames.get(Math.floorMod(themeIndex + 1, themeNames.size())));
                status = "Theme: " + themes.activeTheme();
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
                    status = parts.keymap().actions().stream().anyMatch(action -> action.id().equals(actionId))
                            ? "Action unavailable: " + actionId
                            : "Unhandled action: " + actionId;
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
        ProjectNode selected = selectedProjectNode();
        if (selected != null && selected.directory) {
            selected.expanded = !selected.expanded;
            recomputeVisible(projectFiles.get(Math.min(projectList.selected(), projectFiles.size() - 1)));
            status = (selected.expanded ? "Expanded " : "Collapsed ") + selected.relative;
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
        refreshVcsAsync();
        status = editor.status();
    }

    private void saveActiveFile() {
        if (editor == null) {
            status = "Built-in editor unavailable";
            return;
        }
        editor.save();
        refreshVcsAsync();
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
        closeMenu(parts);
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
        closeMenu(parts);
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
        closeMenu(parts);
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
        String vcsUnavailable = availableVcsProvider() == null
                ? vcsProviders.isEmpty() ? "Version control provider unavailable"
                        : vcsProviders.stream().map(provider -> provider.health().reason())
                                .filter(Objects::nonNull).findFirst().orElse("Version control provider unavailable")
                : "";
        addMenu(entries, "VCS", KeyActions.VCS_REFRESH, "Refresh", vcsUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_SHOW_DIFF, "Show Diff", canEdit ? vcsUnavailable : editorUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_DIFF_WORKING_TREE, "Diff Working Tree", vcsUnavailable);
        String writeUnavailable = vcsWriteInProgress ? "A Git operation is already running" : vcsUnavailable;
        String fileWriteUnavailable = !canEdit ? editorUnavailable : writeUnavailable;
        String branchUnavailable = editor != null && editor.tabs().stream().anyMatch(BuiltinEditor.TabView::dirty)
                ? "Save or close dirty editor tabs before switching branches" : writeUnavailable;
        addMenu(entries, "VCS", KeyActions.VCS_STAGE, "Stage Active File...", fileWriteUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_UNSTAGE, "Unstage Active File...", fileWriteUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_COMMIT, "Commit Staged Changes...", writeUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_SWITCH_BRANCH, "Switch Branch...", branchUnavailable);
        return List.copyOf(entries);
    }

    private NavigationCapability availableNavigationProvider() {
        return navigationProviders.stream().filter(candidate -> candidate.health().isAvailable())
                .findFirst().orElse(null);
    }

    private VersionControlCapability availableVcsProvider() {
        return vcsProviders.stream().filter(candidate -> candidate.health().isAvailable())
                .findFirst().orElse(null);
    }

    public void refreshVcs() {
        refreshVcs(vcsRefreshSequence.incrementAndGet());
    }

    private void refreshVcsAsync() {
        long request = vcsRefreshSequence.incrementAndGet();
        frameLock.lock();
        try {
            status = "Refreshing Git status...";
        } finally {
            frameLock.unlock();
        }
        Thread.ofVirtual().start(() -> refreshVcs(request));
    }

    private void refreshVcs(long request) {
        VersionControlCapability provider = availableVcsProvider();
        if (provider == null) {
            publishVcsRefresh(request, Map.of(), Map.of(), vcsProviders.isEmpty() ? "Version control unavailable"
                    : "Version control unavailable: "
                            + vcsProviders.stream().map(p -> p.health().reason()).filter(Objects::nonNull)
                                    .findFirst().orElse("no healthy provider"));
            return;
        }
        CapabilityResult<VersionControlCapability.Status> result = provider.status();
        if (result instanceof CapabilityResult.Failed<VersionControlCapability.Status> failed) {
            publishVcsRefresh(request, Map.of(), Map.of(), "VCS refresh failed: " + failed.failure().reason());
            return;
        }
        VersionControlCapability.Status statusValue =
                ((CapabilityResult.Ok<VersionControlCapability.Status>) result).value();
        Map<String, VersionControlCapability.State> states = new HashMap<>();
        statusValue.entries().forEach(entry -> states.put(entry.path().replace('\\', '/'), entry.state()));
        Map<String, Set<Integer>> changedLines = new HashMap<>();
        String activeFile = null;
        Path activePath = null;
        frameLock.lock();
        try {
            if (editor != null) {
                activeFile = editor.currentFile().map(file -> editor.project().relativize(file).toString()
                        .replace('\\', '/')).orElse(null);
                if (activeFile != null) { activePath = editor.project().resolve(activeFile); }
            }
        } finally {
            frameLock.unlock();
        }
        if (activeFile != null && activePath != null
                && states.get(activeFile) == VersionControlCapability.State.MODIFIED) {
            CapabilityResult<String> fileDiff = provider.diff(activePath);
            if (fileDiff instanceof CapabilityResult.Ok<String> ok && ok.value() != null) {
                changedLines.put(activeFile, changedLines(ok.value()));
            }
        }
        String branch = statusValue.isDetached() ? "detached HEAD" : statusValue.branch();
        publishVcsRefresh(request, states, changedLines,
                "VCS: " + branch + " (+" + (statusValue.ahead() == null ? 0 : statusValue.ahead())
                        + "/-" + (statusValue.behind() == null ? 0 : statusValue.behind()) + "), "
                        + statusValue.entries().size() + " changed");
    }

    private void publishVcsRefresh(long request, Map<String, VersionControlCapability.State> states,
            Map<String, Set<Integer>> changedLines, String newStatus) {
        frameLock.lock();
        try {
            if (request != vcsRefreshSequence.get()) { return; }
            vcsStates = Map.copyOf(states);
            if (editor != null) { editor.vcsStatus(vcsStates, changedLines); }
            if (!projectFiles.isEmpty()) { projectList.items(projectFileRows()); }
            status = newStatus;
        } finally {
            frameLock.unlock();
        }
    }

    private List<String> projectFileRows() {
        return projectFiles.stream().map(file -> {
            VersionControlCapability.State state = vcsStates.get(file.replace('\\', '/'));
            return state == null ? file : vcsMarker(state) + " " + file;
        }).toList();
    }

    private static String vcsMarker(VersionControlCapability.State state) {
        return switch (state) {
            case ADDED -> "A";
            case DELETED -> "D";
            case MODIFIED -> "M";
            case UNTRACKED -> "?";
            case UNMERGED -> "!";
        };
    }

    static Set<Integer> changedLines(String diffText) {
        Set<Integer> rows = new HashSet<>();
        int newLine = 0;
        boolean inHunk = false;
        for (String line : diffText.split("\\R")) {
            if (line.startsWith("@@")) {
                inHunk = true;
                int plus = line.indexOf('+');
                int end = plus < 0 ? -1 : line.indexOf(' ', plus);
                if (end > plus) {
                    String range = line.substring(plus + 1, end);
                    int comma = range.indexOf(',');
                    try { newLine = Integer.parseInt(comma < 0 ? range : range.substring(0, comma)) - 1; }
                    catch (NumberFormatException ignored) { newLine = 0; }
                }
            } else if (inHunk && line.startsWith("+")) {
                rows.add(Math.max(0, newLine++));
            } else if (inHunk && line.startsWith("-")) {
                rows.add(Math.max(0, newLine - 1));
            } else if (inHunk && line.startsWith(" ")) {
                newLine++;
            }
        }
        return Set.copyOf(rows);
    }

    public void showVcsDiff() {
        VersionControlCapability provider = availableVcsProvider();
        if (provider == null) {
            status = "Version control unavailable";
            return;
        }
        if (editor == null || editor.activeDocument().isEmpty()) {
            status = "Open a project file first";
            return;
        }
        Path file = Path.of(editor.activeDocument().get().file());
        CapabilityResult<String> result = provider.diff(file);
        if (result instanceof CapabilityResult.Failed<String> failed) {
            status = "VCS diff failed: " + failed.failure().reason();
            return;
        }
        String text = ((CapabilityResult.Ok<String>) result).value();
        this.show(new ShellUnifiedDiff(file.toString(), text == null ? "" : text));
        status = "VCS diff: " + file;
    }

    public void showVcsWorkingTreeDiff() {
        VersionControlCapability provider = availableVcsProvider();
        if (provider == null) {
            status = "Version control unavailable";
            return;
        }
        CapabilityResult<String> result = provider.diffWorkingTree();
        if (result instanceof CapabilityResult.Failed<String> failed) {
            status = "VCS diff failed: " + failed.failure().reason();
            return;
        }
        String text = ((CapabilityResult.Ok<String>) result).value();
        this.show(new ShellUnifiedDiff("working tree", text == null ? "" : text));
        status = "VCS diff: working tree";
    }

    private void requestStage(boolean unstage) {
        if (availableVcsProvider() == null) {
            status = "Version control unavailable";
            return;
        }
        if (editor == null || editor.currentFile().isEmpty()) {
            status = "Open a project file first";
            return;
        }
        pendingVcsAction = unstage ? VcsWriteAction.UNSTAGE : VcsWriteAction.STAGE;
        pendingVcsFile = editor.currentFile().orElseThrow();
        pendingVcsValue = "";
        vcsPrompt = VcsPrompt.CONFIRM;
        status = (unstage ? "Unstage " : "Stage ") + projectRelative(pendingVcsFile) + "? [y/N]";
    }

    private void requestCommit() {
        if (availableVcsProvider() == null) {
            status = "Version control unavailable";
            return;
        }
        pendingVcsAction = VcsWriteAction.COMMIT;
        pendingVcsFile = null;
        pendingVcsValue = "";
        vcsPrompt = VcsPrompt.COMMIT_MESSAGE;
        status = "Commit message: " + pendingVcsValue + " (Enter to continue, Esc cancels)";
    }

    private void requestBranchSwitch() {
        if (availableVcsProvider() == null) {
            status = "Version control unavailable";
            return;
        }
        if (editor != null && editor.tabs().stream().anyMatch(BuiltinEditor.TabView::dirty)) {
            status = "Save or close dirty editor tabs before switching branches";
            return;
        }
        pendingVcsAction = VcsWriteAction.SWITCH_BRANCH;
        pendingVcsFile = null;
        pendingVcsValue = "";
        vcsPrompt = VcsPrompt.BRANCH_NAME;
        status = "Local branch name: " + pendingVcsValue + " (Enter to continue, Esc cancels)";
    }

    private void handleVcsPromptKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            cancelVcsPrompt("Git operation cancelled");
            return;
        }
        if (vcsPrompt == VcsPrompt.CONFIRM) {
            if (key.isCharIgnoreCase('y')) {
                vcsPrompt = VcsPrompt.NONE;
                performVcsWrite();
            } else if (key.isCharIgnoreCase('n') || key.isKey(KeyCode.ENTER)) {
                cancelVcsPrompt("Git operation cancelled");
            }
            return;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            if (!pendingVcsValue.isEmpty()) {
                int codePoint = pendingVcsValue.codePointBefore(pendingVcsValue.length());
                pendingVcsValue = pendingVcsValue.substring(0, pendingVcsValue.length() - Character.charCount(codePoint));
            }
        } else if (key.isKey(KeyCode.ENTER)) {
            if (pendingVcsValue.isBlank()) {
                status = vcsPrompt == VcsPrompt.COMMIT_MESSAGE ? "Commit message cannot be empty"
                        : "Branch name cannot be empty";
                return;
            }
            vcsPrompt = VcsPrompt.CONFIRM;
            status = (pendingVcsAction == VcsWriteAction.COMMIT ? "Commit '" : "Switch to branch '")
                    + pendingVcsValue + "'? [y/N]";
        } else if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()
                && !Character.isISOControl(key.codePoint())) {
            int limit = vcsPrompt == VcsPrompt.COMMIT_MESSAGE ? 1000 : 255;
            if (pendingVcsValue.codePointCount(0, pendingVcsValue.length()) < limit) {
                pendingVcsValue += new String(Character.toChars(key.codePoint()));
            }
        }
        if (vcsPrompt == VcsPrompt.COMMIT_MESSAGE) {
            status = "Commit message: " + pendingVcsValue + " (Enter to continue, Esc cancels)";
        } else if (vcsPrompt == VcsPrompt.BRANCH_NAME) {
            status = "Local branch name: " + pendingVcsValue + " (Enter to continue, Esc cancels)";
        }
    }

    private void cancelVcsPrompt(String message) {
        vcsPrompt = VcsPrompt.NONE;
        pendingVcsAction = null;
        pendingVcsFile = null;
        pendingVcsValue = "";
        status = message;
    }

    private void performVcsWrite() {
        VcsWriteAction action = pendingVcsAction;
        Path file = pendingVcsFile;
        String value = pendingVcsValue;
        pendingVcsAction = null;
        pendingVcsFile = null;
        pendingVcsValue = "";
        VersionControlCapability provider = availableVcsProvider();
        if (provider == null) {
            status = "Version control unavailable";
            return;
        }
        vcsWriteInProgress = true;
        status = switch (action) {
            case STAGE -> "Staging " + projectRelative(file) + "...";
            case UNSTAGE -> "Unstaging " + projectRelative(file) + "...";
            case COMMIT -> "Committing staged changes...";
            case SWITCH_BRANCH -> "Switching branch...";
        };
        Thread.ofVirtual().start(() -> {
            CapabilityResult<Boolean> result;
            try {
                result = switch (action) {
                    case STAGE -> provider.stage(file, true);
                    case UNSTAGE -> provider.unstage(file, true);
                    case COMMIT -> provider.commit(value, true);
                    case SWITCH_BRANCH -> provider.switchBranch(value, true);
                };
            } catch (RuntimeException failure) {
                String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                result = CapabilityResult.failed(new CapabilityFailure("Git operation failed", detail));
            }
            long refresh = vcsRefreshSequence.incrementAndGet();
            refreshVcs(refresh);
            frameLock.lock();
            try {
                if (refresh == vcsRefreshSequence.get()) {
                    status = result instanceof CapabilityResult.Ok<Boolean> ok && ok.value()
                            ? vcsWriteSuccess(action, file, value)
                            : "Git operation failed: " + result.failureOf().map(CapabilityFailure::detail)
                                    .filter(detail -> !detail.isBlank()).orElse("the provider rejected the action");
                }
                vcsWriteInProgress = false;
            } finally {
                frameLock.unlock();
            }
        });
    }

    private String vcsWriteSuccess(VcsWriteAction action, Path file, String value) {
        return switch (action) {
            case STAGE -> "Staged " + projectRelative(file);
            case UNSTAGE -> "Unstaged " + projectRelative(file);
            case COMMIT -> "Committed: " + value;
            case SWITCH_BRANCH -> "Switched to " + value + "; reopen tabs to load branch files";
        };
    }

    private String projectRelative(Path file) {
        if (file == null || editor == null) { return ""; }
        try { return editor.project().relativize(file).toString(); }
        catch (IllegalArgumentException outsideProject) { return file.toString(); }
    }

    private static void addMenu(List<MenuEntry> entries, String menu, String action, String label, String reason) {
        entries.add(new MenuEntry(menu, action, label, reason));
    }

    private void openMenu(KeymapParts parts, int index) {
        parts.menuPopup().open(index);
        syncEditorMenuNavigation(parts);
    }

    private void closeMenu(KeymapParts parts) {
        parts.menuPopup().close();
        syncEditorMenuNavigation(parts);
    }

    private void syncEditorMenuNavigation(KeymapParts parts) {
        if (editor != null) {
            editor.menuNavigationActive(parts.menuPopup().isOpen());
        }
    }

    /**
     * Closes the overlays; the keymap runs the closing at every non overlay
     * dispatch, so the shell returns to navigation with the next action.
     */
    private boolean closeOverlays(KeymapParts parts) {
        parts.commandPalette().close();
        parts.keymapHelp().close();
        closeMenu(parts);
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
        focusToolWindow(window);
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
        if (active instanceof RunToolWindow && !active.id().equals(focusedToolWindowId)) {
            return false;
        }
        return handleToolWindowKey(overlays(), active, key);
    }

    private boolean handleToolWindowKey(KeymapParts parts, ToolWindow window, KeyEvent key) {
        String action = window.actionForKey(key);
        if (action != null) {
            dispatch(parts, action, key);
            return true;
        }
        return window.handleKey(key);
    }

    EventResult routeToolWindowKey(ToolWindow window, KeyEvent key) {
        frameLock.lock();
        ActionSurface previous = actionSurface.get();
        actionSurface.set(ActionSurface.TUI);
        try {
            return handleToolWindowKey(overlays(), window, key) ? EventResult.HANDLED : EventResult.UNHANDLED;
        } finally {
            actionSurface.set(previous);
            frameLock.unlock();
        }
    }

    private ToolWindow activeBottomWindow() {
        List<ToolWindow> bottomWindows = windowsByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, List.of());
        int index = activeToolIndexByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, 0);
        return index >= 0 && index < bottomWindows.size() ? bottomWindows.get(index) : null;
    }

    private void focusToolWindow(ToolWindow window) {
        focusedToolWindowId = window.id();
    }

    private static boolean isCommandAction(String actionId) {
        return KeyActions.RUN_EXECUTE.equals(actionId) || KeyActions.RUN_STOP.equals(actionId)
                || KeyActions.TERMINAL_EXECUTE.equals(actionId);
    }

    private boolean actionAllowed(ActionSurface surface, String actionId) {
        String allowed = switch (surface) {
            case TUI -> tuiAllowedActions;
            case WEB -> webAllowedActions;
            case MCP -> mcpAllowedActions;
        };
        return java.util.Arrays.stream(allowed.split(","))
                .map(String::trim)
                .anyMatch(value -> value.equals("*") || value.equals(actionId));
    }

    public void moveSelection(int delta) {
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
        focusedToolWindowId = null;
        pendingProjectFocus = true;
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
            closeMenu(current);
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

    public List<String> projectFiles() {
        return projectFiles;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path selectedPath() {
        int selected = projectList.selected();
        if (selected < 0 || selected >= projectFiles.size()) {
            return null;
        }
        return projectRoot.resolve(projectFiles.get(selected));
    }

    public String selectedPathString() {
        int selected = projectList.selected();
        if (selected < 0 || selected >= projectFiles.size()) {
            return "";
        }
        return projectFiles.get(selected);
    }

    private ProjectNode selectedProjectNode() {
        int selected = projectList.selected();
        if (selected < 0 || selected >= visibleNodeObjects.size()) {
            return null;
        }
        return visibleNodeObjects.get(selected);
    }

    public void expandSelected() {
        ProjectNode node = selectedProjectNode();
        if (node != null && node.directory && !node.expanded) {
            node.expanded = true;
            recomputeVisible(selectedPathString());
            status = "Expanded " + node.relative;
        }
    }

    public void collapseSelected() {
        ProjectNode node = selectedProjectNode();
        if (node != null && node.directory && node.expanded) {
            node.expanded = false;
            recomputeVisible(selectedPathString());
            status = "Collapsed " + node.relative;
        }
    }

    public void toggleSelected() {
        ProjectNode node = selectedProjectNode();
        if (node != null && node.directory) {
            node.expanded = !node.expanded;
            recomputeVisible(selectedPathString());
            status = (node.expanded ? "Expanded " : "Collapsed ") + node.relative;
        }
    }

    private void beginProjectInput(InputMode mode) {
        if (fileOperations == null) {
            status = "Project file operations unavailable";
            return;
        }
        if (mode == InputMode.RENAME && selectedProjectNode() == null) {
            status = "No selection";
            return;
        }
        projectInputMode = mode;
        projectInput.setLength(0);
        pendingProjectFocus = true;
        updateProjectPrompt();
    }

    private void updateProjectPrompt() {
        String label = switch (projectInputMode) {
            case CREATE_FILE -> "New file";
            case CREATE_DIRECTORY -> "New directory";
            case RENAME -> "Rename";
            case NONE -> "";
        };
        status = label + ": " + projectInput + " (Enter to confirm, Escape to cancel)";
    }

    private boolean handleProjectKey(KeyEvent key) {
        if (projectInputMode != InputMode.NONE) {
            if (key.isKey(KeyCode.ESCAPE)) {
                projectInputMode = InputMode.NONE;
                status = "Project action cancelled";
            } else if (key.isKey(KeyCode.ENTER)) {
                String name = projectInput.toString();
                InputMode mode = projectInputMode;
                projectInputMode = InputMode.NONE;
                switch (mode) {
                    case CREATE_FILE -> createFile(name);
                    case CREATE_DIRECTORY -> createDirectory(name);
                    case RENAME -> renameSelected(name);
                    case NONE -> { }
                }
            } else if (key.isKey(KeyCode.BACKSPACE) && !projectInput.isEmpty()) {
                projectInput.delete(projectInput.offsetByCodePoints(projectInput.length(), -1), projectInput.length());
                updateProjectPrompt();
            } else if (key.code() == KeyCode.CHAR && key.codePoint() >= 32 && !key.hasCtrl() && !key.hasAlt()) {
                projectInput.appendCodePoint(key.codePoint());
                updateProjectPrompt();
            }
            return true;
        }
        if (pendingDeletePath != null) {
            if (key.isCharIgnoreCase('y')) {
                deleteSelectedConfirmed();
            } else if (key.isCharIgnoreCase('n') || key.isKey(KeyCode.ESCAPE)) {
                cancelDelete();
            }
            return true;
        }
        KeymapParts parts = overlays();
        if (inputCaptured || parts.menuPopup().isOpen() || parts.commandPalette().isOpen()
                || parts.keymapHelp().isOpen() || (parts.searchModel() != null && parts.searchModel().isOpen())) {
            return false;
        }
        if (parts.keymap().matches(key, KeyActions.PROJECT_NEW_FILE)) {
            beginProjectInput(InputMode.CREATE_FILE);
        } else if (parts.keymap().matches(key, KeyActions.PROJECT_NEW_DIRECTORY)) {
            beginProjectInput(InputMode.CREATE_DIRECTORY);
        } else if (parts.keymap().matches(key, KeyActions.PROJECT_RENAME)) {
            beginProjectInput(InputMode.RENAME);
        } else if (parts.keymap().matches(key, KeyActions.PROJECT_DELETE)) {
            requestDeleteSelected();
        } else if (parts.keymap().matches(key, KeyActions.PROJECT_REFRESH)) {
            refreshProjectTree();
        } else {
            return false;
        }
        return true;
    }

    public boolean createFile(String name) {
        return createEntry(name, false);
    }

    public boolean createDirectory(String name) {
        return createEntry(name, true);
    }

    private boolean createEntry(String name, boolean directory) {
        if (name == null || name.isBlank()) {
            status = "A name is required";
            return false;
        }
        ProjectNode parent = selectedProjectNode();
        if (parent != null && !parent.directory) {
            parent = parent.parent;
        }
        if (fileOperations == null) {
            status = "Project file operations unavailable";
            return false;
        }
        Path parentRelative = parent == null ? Path.of("") : parent.relative;
        CapabilityResult<Path> result = directory ? fileOperations.createDirectory(parentRelative, name)
                : fileOperations.createFile(parentRelative, name);
        if (result instanceof CapabilityResult.Failed<Path> failed) {
            status = "Create failed: " + failed.failure().detail();
            return false;
        }
        if (name.startsWith(".")) {
            showHiddenFiles = true;
        }
        if (parent != null) {
            parent.expanded = true;
        }
        Path newRelative = parentRelative.resolve(name);
        refreshProjectTree();
        selectPath(newRelative.toString().replace('\\', '/'));
        status = (directory ? "Directory" : "File") + " created: " + newRelative;
        return true;
    }

    public boolean renameSelected(String newName) {
        ProjectNode node = selectedProjectNode();
        if (node == null) {
            status = "No selection";
            return false;
        }
        if (newName == null || newName.isBlank()) {
            status = "A name is required";
            return false;
        }
        if (fileOperations == null) {
            status = "Project file operations unavailable";
            return false;
        }
        CapabilityResult<Path> result = fileOperations.rename(node.relative, newName);
        if (result instanceof CapabilityResult.Failed<Path> failed) {
            status = "Rename failed: " + failed.failure().detail();
            return false;
        }
        Path parent = node.relative.getParent();
        Path newRelative = parent == null ? Path.of(newName) : parent.resolve(newName);
        refreshProjectTree();
        selectPath(newRelative.toString().replace('\\', '/'));
        status = "Renamed to " + newName;
        return true;
    }

    public void requestDeleteSelected() {
        ProjectNode node = selectedProjectNode();
        if (node == null) {
            status = "No selection";
            return;
        }
        pendingDeletePath = node.relative.toString().replace('\\', '/');
        status = "Delete '" + node.name + "'? (y/n)";
    }

    public boolean deleteSelectedConfirmed() {
        if (pendingDeletePath == null || pendingDeletePath.isBlank()) {
            return false;
        }
        if (fileOperations == null) {
            status = "Project file operations unavailable";
            return false;
        }
        CapabilityResult<Path> result = fileOperations.delete(Path.of(pendingDeletePath));
        if (result instanceof CapabilityResult.Failed<Path> failed) {
            status = "Delete failed: " + failed.failure().detail();
            return false;
        }
        String deleted = pendingDeletePath;
        pendingDeletePath = null;
        refreshProjectTree();
        status = "Deleted " + deleted;
        return true;
    }

    public void confirmDelete() {
        deleteSelectedConfirmed();
    }

    public void cancelDelete() {
        pendingDeletePath = null;
        status = "Delete cancelled";
    }

    public boolean isDeletePending() {
        return pendingDeletePath != null;
    }

    public void copySelectedPath() {
        ProjectNode node = selectedProjectNode();
        if (node == null) {
            status = "No selection";
            return;
        }
        copiedPath = projectRoot.resolve(node.relative.toString().replace('/', java.io.File.separatorChar));
        status = "Copied path: " + copiedPath;
    }

    public Path copiedPath() {
        return copiedPath;
    }

    public void revealFile(Path file) {
        if (file == null) {
            return;
        }
        try {
            Path relative = projectRoot.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize());
            String relativeText = relative.toString().replace('\\', '/');
            Path accumulated = Path.of("");
            for (Path part : relative) {
                accumulated = accumulated.resolve(part);
                ProjectNode node = findProjectNode(accumulated.toString().replace('\\', '/'));
                if (node != null) {
                    node.expanded = true;
                }
            }
            refreshProjectTree();
            selectPath(relativeText);
        } catch (IllegalArgumentException outside) {
            status = "File outside project";
        }
    }

    public void revealActiveFile() {
        if (editor == null || editor.activeDocument() == null) {
            status = "No active file";
            return;
        }
        revealFile(Path.of(editor.activeDocument().orElseThrow().file()));
    }

    public void toggleShowHiddenFiles() {
        setShowHiddenFiles(!showHiddenFiles);
    }

    public void setShowHiddenFiles(boolean value) {
        showHiddenFiles = value;
        if (projectSearch != null) {
            refreshProjectTree();
        }
    }

    public boolean showHiddenFiles() {
        return showHiddenFiles;
    }

    private ProjectNode findProjectNode(String relative) {
        List<ProjectNode> current = rootNodes;
        ProjectNode found = null;
        Path accumulated = Path.of("");
        for (String part : relative.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            accumulated = accumulated.resolve(part);
            ProjectNode next = null;
            for (ProjectNode candidate : current) {
                if (candidate.relative.equals(accumulated)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            found = next;
            current = next.children == null ? List.of() : next.children;
        }
        return found;
    }

    public void selectPath(String relative) {
        int index = projectFiles.indexOf(relative);
        if (index >= 0) {
            projectList.selected(index);
            syncedFileIndex = index;
            state.selectFile(relative);
            status = "Selected: " + relative;
        }
    }

    public String status() {
        return status;
    }

    public CapabilityRegistry capabilityRegistry() {
        return capabilityRegistry;
    }

    public List<CapabilityStatus> statusBarWidgets() {
        return capabilityRegistry == null ? List.of() : capabilityRegistry.snapshot();
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
    private record LockedElement(Element delegate, ReentrantLock lock, VintelliShell projectOwner) implements Element {

        @Override
        public void render(Frame frame, Rect area, RenderContext context) {
            lock.lock();
            try {
                if (projectOwner != null && projectOwner.pendingProjectFocus && context instanceof DefaultRenderContext drc) {
                    drc.focusManager().setFocus("project-panel");
                    projectOwner.pendingProjectFocus = false;
                }
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
                if (focused && projectOwner != null && projectOwner.handleProjectKey(event)) {
                    return EventResult.HANDLED;
                }
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

    private static final class ProjectNode {
        Path relative;
        String name;
        boolean directory;
        List<ProjectNode> children = new ArrayList<>();
        ProjectNode parent;
        boolean expanded;

        ProjectNode(Path relative, String name, boolean directory) {
            this.relative = relative;
            this.name = name;
            this.directory = directory;
        }
    }

    private enum InputMode {
        NONE, CREATE_FILE, CREATE_DIRECTORY, RENAME
    }
}
