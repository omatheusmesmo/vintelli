package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellDiffViewPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellStateView;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import dev.omatheusmesmo.vintelli.tui.ProjectTreeController.InputMode;
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
import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidgetContributor;
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
import dev.tamboui.toolkit.elements.RichTextElement;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.Spacer;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.StackElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.GlobalEventHandler;
import dev.tamboui.toolkit.focus.FocusManager;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.style.Style;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.arc.All;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;


/**
 * IntelliJ-like terminal shell: main toolbar, tool window stripes (left, right, bottom),
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
 * page keys, Home/End and the wheel are consumed by the focused list itself
 * and row clicks are routed by the shell, so {@link #fileIndex()}, the
 * {@code ShellStateSnapshot} read model and the status line are read back from
 * the list on every handled key and every frame.
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
    static final int ACP_SIDE_PANEL_WIDTH = 32;
    static final int TOOL_STRIP_WIDTH = ToolStripElement.WIDTH;
    private static final int PANE_SEPARATOR_WIDTH = 1;
    private static final int TOOL_TAB_ROWS = ToolTabBarElement.ROWS;
    private static final int BOTTOM_HEIGHT = TOOL_WINDOW_HEIGHT + TOOL_TAB_ROWS + 1;

    private static final int MAX_EDITOR_DIFF_LINES = 6;
    private static final int MAX_EDITOR_LINE_LENGTH = 40;

    private static final List<String> MENUS = List.of("File", "Edit", "View", "Navigate", "Code", "Refactor", "Run", "Tools", "VCS", "Help");
    private static final String EMPTY_TOOL_WINDOWS_TEXT = "no tool window beans registered; implement ToolWindow and expose the bean";
    private static final String EDITOR_TEXT = "editor tool window lands with issue #31; vintelli needs no external editor";

    /**
     * The keymap-driven parts of the shell, built together the first time the
     * TUI session actually uses them.
     */
    private record KeymapParts(Keymap keymap, CommandPalette commandPalette, KeymapHelpOverlay keymapHelp,
            MenuPopup menuPopup, ProjectSearchModel searchModel, ProjectSearchOverlay searchOverlay) {
    }

    private enum SplitterDrag { PROJECT, BOTTOM }


    private final ShellThemes themes;
    private final ShellState state;
    private final List<ToolWindow> toolWindows;
    private final BuiltinEditor editor;
    private final ProjectSearch projectSearch;
    private final List<NavigationCapability> navigationProviders;
    private final CapabilityRegistry capabilityRegistry;
    private final AtomicLong readFileSequence = new AtomicLong();
    private volatile CapabilityStateLine.Summary readFileSummary;
    private final MainToolbar mainToolbar;
    private final ToolbarController toolbar;
    private final RunToolWindow runWindow;
    private final IdeaMcpConnection ideaMcpConnection;
    private final ToolStripes toolStripes;
    private final StatusBar statusBar;
    private final ProjectFileOperations fileOperations;
    private final ShellLayout shellLayout;
    private final ShellLayoutStore layoutStore;
    private volatile DockElement layoutDock;
    private volatile DockElement bottomDock;
    private volatile FocusManager focusManager;
    private volatile Rect activeToolWindowArea = Rect.ZERO;
    private volatile Rect activeRightToolWindowArea = Rect.ZERO;
    private Rect shellArea = Rect.ZERO;
    private SplitterDrag splitterDrag;
    private boolean splitterChanged;
    private int nextDiagnosticIndex;
    private final Map<ToolWindow.Anchor, List<ToolWindow>> windowsByAnchor;
    private final ReentrantLock frameLock = new ReentrantLock();
    private final AtomicBoolean ideaConnectInProgress = new AtomicBoolean();
    private final Supplier<Keymap> keymapSupplier;
    private final StackElement rootBase;
    private final Element lockedRoot;
    private final Map<ToolWindow.Anchor, ToolTabBarElement> tabBars = new ConcurrentHashMap<>();
    private final ProjectTreeController projects;
    private final VcsActions vcsActions;
    private final ProjectPanel projectPanel;
    private Column centerPanel;
    private Column paneSeparatorVertical;
    private Column paneSeparatorHorizontal;
    private final Path projectRoot;
    private final FileIcons fileIcons;
    private boolean pendingProjectFocus;
    private boolean navigationBarFocused;
    private int selectedNavigationSegment;
    private List<StatusBar.Breadcrumb> navigationPaths = List.of();
    private Path navigationSymbolsFile;
    private List<NavigationCapability.DocumentSymbol> navigationSymbols = List.of();
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

    // Active tool window index per anchor (for stripe tab selection)
    private final Map<ToolWindow.Anchor, Integer> activeToolIndexByAnchor = new ConcurrentHashMap<>();

    @Inject
    public VintelliShell(ToolWindowRegistry registry, ShellState state, KeymapRegistry keymapRegistry,
            Instance<ShellThemes> themes, ProjectSearch projectSearch,
            @All List<NavigationCapability> navigationProviders, CapabilityRegistry capabilityRegistry,
            @All List<StatusBarWidgetContributor> statusBarContributors,
            @All List<VersionControlCapability> vcsProviders, @All List<ProjectFileOperations> fileOperations,
            Instance<IdeaMcpConnection> ideaMcpConnections, UiWorkQueue uiWorkQueue) {
        this(registry.toolWindows(), state, keymapRegistry::get, themes.get(), projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, true, statusBarContributors,
                fileOperations.isEmpty() ? null : fileOperations.getFirst(),
                ideaMcpConnections.isResolvable() ? ideaMcpConnections.get() : null);
        this.uiWorkQueue = uiWorkQueue;
    }

    void useUiWorkQueue(UiWorkQueue uiWorkQueue) {
        this.uiWorkQueue = uiWorkQueue;
    }

    void useClock(LongSupplier clock) {
        projects.useClock(clock);
    }

    void useUnicodeProjectTree(boolean unicode) {
        projectPanel.useUnicodeTree(unicode);
    }

    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes) {
        this(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(), null, List.of(),
                false, toolWindows, null);
    }

    VintelliShell(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes, ShellLayoutStore layoutStore) {
        this(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(), null, List.of(),
                false, toolWindows, null, layoutStore);
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
        this(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders, capabilityRegistry,
                vcsProviders, persistLayout, statusBarContributors, fileOperations, (IdeaMcpConnection) null);
    }

    VintelliShell(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes,
            IdeaMcpConnection ideaMcpConnection) {
        this(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(), null, List.of(),
                false, toolWindows, null, ideaMcpConnection, null);
    }

    private VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders,
            CapabilityRegistry capabilityRegistry, List<VersionControlCapability> vcsProviders,
            boolean persistLayout, List<? extends StatusBarWidgetContributor> statusBarContributors,
            ProjectFileOperations fileOperations, ShellLayoutStore layoutStoreOverride) {
        this(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders, capabilityRegistry,
                vcsProviders, persistLayout, statusBarContributors, fileOperations, (IdeaMcpConnection) null,
                layoutStoreOverride);
    }

    private VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders,
            CapabilityRegistry capabilityRegistry, List<VersionControlCapability> vcsProviders,
            boolean persistLayout, List<? extends StatusBarWidgetContributor> statusBarContributors,
            ProjectFileOperations fileOperations, IdeaMcpConnection ideaMcpConnection) {
        this(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders, capabilityRegistry,
                vcsProviders, persistLayout, statusBarContributors, fileOperations, ideaMcpConnection, null);
    }

    private VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders,
            CapabilityRegistry capabilityRegistry, List<VersionControlCapability> vcsProviders,
            boolean persistLayout, List<? extends StatusBarWidgetContributor> statusBarContributors,
            ProjectFileOperations fileOperations, IdeaMcpConnection ideaMcpConnection,
            ShellLayoutStore layoutStoreOverride) {
        this.themes = themes;
        this.state = state;
        this.projectSearch = projectSearch;
        this.fileOperations = fileOperations;
        this.navigationProviders = List.copyOf(navigationProviders);
        this.capabilityRegistry = capabilityRegistry;
        this.readFileSummary = capabilityRegistry == null
                ? CapabilityStateLine.notConfigured(CapabilityType.READ_FILE)
                : CapabilityStateLine.checking(CapabilityType.READ_FILE);
        this.ideaMcpConnection = ideaMcpConnection;
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
                        revealFile(file);
                        releaseCapture();
                        openFile(file, problem.startLine());
                    } catch (IOException unavailable) {
                        status = "Diagnostic file is unavailable";
                    }
                }));
        Path resolvedRoot = editor == null ? Path.of("").toAbsolutePath().normalize() : editor.project();
        try {
            if (Files.exists(resolvedRoot)) {
                resolvedRoot = resolvedRoot.toRealPath();
            }
        } catch (IOException ignored) {
        }
        this.projectRoot = resolvedRoot.normalize();
        this.projects = new ProjectTreeController(this.projectRoot, state, projectSearch, fileOperations,
                message -> status = message, this::refreshProjectTree, this::refreshVcs);
        this.vcsActions = new VcsActions(vcsProviders, frameLock, message -> status = message, this::show,
                projects, editor);
        this.fileIcons = FileIcons.resolve();
        Path projectRoot = editor == null ? Path.of("").toAbsolutePath().normalize() : editor.project();
        Path configRoot = persistLayout ? ConfigDirectory.configRoot(ConfigDirectory.sharedConfigDir(),
                System.getenv("XDG_CONFIG_HOME"), System.getenv("APPDATA"), System.getProperty("user.home")) : null;
        this.layoutStore = layoutStoreOverride == null ? new ShellLayoutStore(configRoot, projectRoot)
                : layoutStoreOverride;
        Set<String> allWindowIds = this.toolWindows.stream().map(ToolWindow::id).collect(Collectors.toSet());
        Set<String> defaultVisibleWindowIds = this.toolWindows.stream()
                .filter(window -> window.anchor() != ToolWindow.Anchor.RIGHT)
                .map(ToolWindow::id)
                .collect(Collectors.toSet());
        this.shellLayout = layoutStore.load(allWindowIds, defaultVisibleWindowIds);
        this.windowsByAnchor = this.toolWindows.stream()
                .collect(Collectors.groupingBy(ToolWindow::anchor, Collectors.toList()));
        this.keymapSupplier = keymapSupplier;
        if (!this.toolWindows.isEmpty()) {
            state.selectToolWindow(this.toolWindows.get(0).id());
            inputCaptured = this.toolWindows.get(0).capturesInput();
        }
        projects.start();
        this.projectPanel = new ProjectPanel(projects, fileIcons, ProjectTreeGlyphs.supportsUnicode(), frameLock,
                projectHost());
        this.runWindow = this.toolWindows.stream().filter(RunToolWindow.class::isInstance)
                .map(RunToolWindow.class::cast).findFirst().orElse(null);
        this.toolbar = new ToolbarController(projectRoot.getFileName() == null ? projectRoot.toString()
                : projectRoot.getFileName().toString(), runWindow, this::toolbarBranch, toolbarHost());
        this.mainToolbar = new MainToolbar(toolbar::model, MENUS, fileIcons.mode(), () -> themes);
        this.toolStripes = new ToolStripes(windowsByAnchor, fileIcons.mode(), stripHost());
        this.statusBar = new StatusBar(() -> status, () -> projects.inputActive() || vcsActions.promptOpen()
                || projects.deletePending(), capabilityRegistry, editor, statusBarContributors);
        this.rootBase = buildRootContent();
        this.lockedRoot = new LockedElement(rootBase, frameLock, this, true);
        // Initialize active index for each anchor to first window
        for (ToolWindow.Anchor anchor : ToolWindow.Anchor.values()) {
            List<ToolWindow> windows = visibleWindows(anchor);
            if (!windows.isEmpty()) {
                int selected = anchor == ToolWindow.Anchor.RIGHT
                        ? windows.stream().map(ToolWindow::id).toList().indexOf("structure")
                        : -1;
                activeToolIndexByAnchor.put(anchor, selected < 0 ? 0 : selected);
            }
        }
        if (projectSearch != null) {
            Thread.ofVirtual().start(this::discoverProjectFiles);
        }
        refreshReadFileSummary();
        vcsActions.refreshToolbarVcs();
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
                                    keymap::describeBindings, MainToolbar.MENU_ORIGIN), searchModel,
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
        boolean customized = shellLayout.isSizeCustomized();
        Constraint projectWidth = Constraint.length(leftRegionWidth(customized
                ? sizedProjectWidth() : PROJECT_PANEL_WIDTH));
        DockElement editorDock = Toolkit.dock()
                .center(centerPanel())
                .bottom(bottomStripe())
                .bottomHeight(bottomPaneConstraint(customized));
        editorDock.id("editor-dock");
        bottomDock = editorDock;
        DockElement dock = Toolkit.dock()
                .top(mainToolbar)
                .left(leftStripe())
                .center(editorDock)
                .right(rightStripe())
                .bottom(Toolkit.lazy(this::statusBarEntry))
                .topHeight(Constraint.length(1))
                .leftWidth(projectWidth)
                .rightWidth(Constraint.length(rightStripeWidth()))
                .bottomHeight(Constraint.length(1));
        dock.id("shell");
        layoutDock = dock;
        return new PaneGapElement(dock, themes, projectPanel::unicodeTree);
    }

    /**
     * The height of the bottom tool windows inside the editor dock. The stored
     * bottom height counts the status row, which now runs under the whole
     * shell, so the pane takes one row less than the stored value.
     */
    private Constraint bottomPaneConstraint(boolean customized) {
        if (bottomWindowsHidden()) {
            return Constraint.length(0);
        }
        int stored = customized ? shellLayout.bottomHeight(availableHeight()) : defaultBottomHeight();
        return Constraint.length(bottomPaneRows(stored));
    }

    private static int bottomPaneRows(int storedHeight) {
        return Math.max(0, storedHeight - 1);
    }

    /**
     * The branch the toolbar shows, or null when the branch widget is hidden. It
     * reads the volatile VCS state the capabilities fill off the render thread,
     * so the toolbar never calls a capability while it renders.
     */
    private String toolbarBranch() {
        VcsActions.ToolbarVcs vcs = vcsActions.toolbarVcs();
        return vcs.visible() ? vcs.branch() : null;
    }

    private ToolbarController.Host toolbarHost() {
        return new ToolbarController.Host() {
            @Override
            public boolean menuOpen() {
                return overlays().menuPopup().isOpen();
            }

            @Override
            public int menuIndex() {
                return overlays().menuPopup().menuIndex();
            }

            @Override
            public void showMenu(int index) {
                VintelliShell.this.showMenu(overlays(), index);
            }

            @Override
            public void closeMenu() {
                VintelliShell.this.closeMenu(overlays());
            }

            @Override
            public void refreshBranch() {
                vcsActions.refreshVcsAsync();
            }

            @Override
            public void focusRunWindow() {
                focusToolWindow(runWindow);
            }

            @Override
            public void dispatch(String action) {
                VintelliShell.this.dispatch(overlays(), action);
            }
        };
    }

    private ToolStripes.Host stripHost() {
        return new ToolStripes.Host() {
            @Override
            public boolean projectVisible() {
                return shellLayout.isProjectVisible();
            }

            @Override
            public boolean windowVisible(String id) {
                return shellLayout.isVisible(id);
            }

            @Override
            public ToolWindow activeWindow(ToolWindow.Anchor anchor) {
                return VintelliShell.this.activeToolWindow(anchor);
            }

            @Override
            public void toggleProjectPanel() {
                VintelliShell.this.toggleProjectPanel();
            }

            @Override
            public void switchTo(ToolWindow window) {
                VintelliShell.this.switchToToolWindow(toolWindows.indexOf(window));
            }

            @Override
            public void toggleWindow(String id) {
                VintelliShell.this.toggleToolWindow(id);
            }
        };
    }

    private Element leftStripe() {
        Row left = Toolkit.row().id("left-stripe");
        left.add(toolStripes.leftElement());
        if (shellLayout.isProjectVisible()) {
            left.add(leftPanel());
        }
        paneSeparatorVertical = separator("pane-separator-vertical");
        paneSeparatorVertical.length(shellLayout.isProjectVisible() ? PANE_SEPARATOR_WIDTH : 0);
        left.add(paneSeparatorVertical);
        left.fill();
        return left;
    }

    private Element leftPanel() {
        List<ToolWindow> leftWindows = visibleWindows(ToolWindow.Anchor.LEFT);
        if (leftWindows.isEmpty()) {
            return projectPane();
        }
        Column column = Toolkit.column().id("left-panel");
        column.add(projectPane());
        column.add(toolWindowStripe(ToolWindow.Anchor.LEFT));
        column.fill();
        return column;
    }

    private Element rightStripe() {
        ToolStripElement rightStrip = toolStripes.rightElement();
        if (rightStrip == null) {
            return null;
        }
        Row stripe = Toolkit.row().id("right-stripe");
        if (!visibleWindows(ToolWindow.Anchor.RIGHT).isEmpty()) {
            Column column = Toolkit.column().id("right-tool-stripe");
            column.add(toolWindowStripe(ToolWindow.Anchor.RIGHT));
            column.fill();
            stripe.add(separator("pane-separator-right"));
            stripe.add(column);
        }
        stripe.add(rightStrip);
        stripe.fill();
        return stripe;
    }

    private Element bottomStripe() {
        if (windowsByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, List.of()).isEmpty()) {
            TextElement empty = Toolkit.text(EMPTY_TOOL_WINDOWS_TEXT);
            empty.id("tool-windows-empty");
            empty.length(TOOL_WINDOW_HEIGHT + 1);
            return empty;
        }
        Column column = Toolkit.column().id("bottom-stripe");
        column.add(toolWindowStripe(ToolWindow.Anchor.BOTTOM));
        column.fill();
        return column;
    }

    private boolean hasBottomWindows() {
        return !windowsByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, List.of()).isEmpty();
    }

    private boolean bottomWindowsHidden() {
        return hasBottomWindows() && visibleWindows(ToolWindow.Anchor.BOTTOM).isEmpty();
    }

    private int leftRegionWidth(int projectWidth) {
        return TOOL_STRIP_WIDTH + (shellLayout.isProjectVisible() ? projectWidth + PANE_SEPARATOR_WIDTH : 0);
    }

    private String tabUnderline() {
        return fileIcons.mode() == IconMode.ASCII ? "-" : "━";
    }

    private Element toolWindowStripe(ToolWindow.Anchor anchor) {
        List<ToolWindow> windows = visibleWindows(anchor);
        if (windows.isEmpty()) {
            tabBars.remove(anchor);
            return Toolkit.text("").length(0);
        }
        List<ToolTabBarElement.Label> labels = windows.stream()
                .map(w -> new ToolTabBarElement.Label(String.valueOf(w.shortcut()),
                        anchor == ToolWindow.Anchor.RIGHT ? compactRightTabTitle(w) : w.title()))
                .toList();
        int activeIdx = activeToolIndexByAnchor.getOrDefault(anchor, 0);
        int clampedTab = Math.min(Math.max(activeIdx, 0), windows.size() - 1);
        ToolTabBarElement tabs = new ToolTabBarElement("tool-tabs-" + anchor.name().toLowerCase(Locale.ROOT),
                labels, clampedTab, tabUnderline());
        tabBars.put(anchor, tabs);

        Column column = Toolkit.column().id("tool-stripe-" + anchor.name().toLowerCase(Locale.ROOT));
        column.add(tabs);
        column.add(Toolkit.lazy(() -> activeToolWindowElement(anchor)));
        column.fill();
        return column;
    }

    private static String compactRightTabTitle(ToolWindow window) {
        return switch (window.id()) {
            case "acp.agents" -> "ACP";
            case "structure" -> "Struct";
            default -> window.title();
        };
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
        if (wasVisible && !shellLayout.isVisible(id)) {
            ToolWindow target = toolWindows.stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
            if (target != null) {
                releaseHiddenWindow(target);
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

    private void releaseHiddenWindow(ToolWindow hidden) {
        if (capturedWindow == hidden) {
            inputCaptured = false;
            capturedWindow = null;
        }
        if (hidden.id().equals(focusedToolWindowId)) {
            focusedToolWindowId = null;
            FocusManager manager = focusManager;
            if (manager != null) {
                manager.setFocus("editor-panel");
            }
        }
    }

    private void requestProjectFocus() {
        if (!shellLayout.isProjectVisible()) {
            shellLayout.setProjectVisible(true);
            persistLayout();
        }
        pendingProjectFocus = true;
    }

    /**
     * Shows or hides the project panel, the left column next to the strip. The
     * panel is not a tool window, so its visibility is kept apart from the
     * tool window set in {@link ShellLayout}.
     */
    private void toggleProjectPanel() {
        boolean visible = !shellLayout.isProjectVisible();
        shellLayout.setProjectVisible(visible);
        persistLayout();
        FocusManager manager = focusManager;
        if (!visible && manager != null && "project-panel".equals(manager.focusedId())) {
            manager.setFocus("editor-panel");
        }
        status = visible ? "Project panel shown" : "Project panel hidden";
    }

    private boolean toolChromeClicked(MouseEvent mouse) {
        Optional<String> icon = toolStripes.iconAt(mouse.x(), mouse.y());
        if (icon.isPresent()) {
            toolStripes.click(icon.get());
            return true;
        }
        for (Map.Entry<ToolWindow.Anchor, ToolTabBarElement> entry : tabBars.entrySet()) {
            int index = entry.getValue().tabAt(mouse.x(), mouse.y());
            List<ToolWindow> windows = visibleWindows(entry.getKey());
            if (index >= 0 && index < windows.size()) {
                switchToToolWindow(toolWindows.indexOf(windows.get(index)));
                return true;
            }
        }
        return false;
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
        ToolWindow window = windows.get(clamped);
        Column pane = Toolkit.column().id("tool-pane-" + window.id());
        pane.add(new PaneHeader("tool-" + window.id(), context -> isToolWindowFocused(window.id()), window::title,
                () -> themes, this::focusMark));
        pane.add(new ToolWindowElement(window, -1, this));
        return anchor == ToolWindow.Anchor.BOTTOM ? pane.length(TOOL_WINDOW_HEIGHT) : pane.fill();
    }

    private Element projectPane() {
        Column pane = Toolkit.column().id("project-pane");
        pane.add(projectPanel.header(() -> themes, this::focusMark));
        pane.add(projectPanel);
        pane.add(new PaneHeader("project-read-file", context -> false, this::readFileTitle, () -> themes, () -> " ",
                false));
        pane.fill();
        return pane;
    }

    private String readFileTitle() {
        CapabilityStateLine.Summary summary = readFileSummary;
        if (summary == null) {
            return "";
        }
        String skipped = summary.skipped().isEmpty() ? "" : "  " + String.join("  ·  ", summary.skipped());
        return summary.headline() + skipped;
    }

    private void refreshReadFileSummary() {
        if (capabilityRegistry == null) {
            return;
        }
        long sequence = readFileSequence.incrementAndGet();
        Thread.ofVirtual().name("vintelli-read-file-state").start(() -> {
            CapabilityStateLine.Summary summary = CapabilityStateLine.read(capabilityRegistry, CapabilityType.READ_FILE);
            if (sequence == readFileSequence.get()) {
                readFileSummary = summary;
            }
        });
    }

    private ProjectPanel.Host projectHost() {
        return new ProjectPanel.Host() {
            @Override
            public Keymap keymap() {
                return overlays().keymap();
            }

            @Override
            public boolean shortcutsBlocked() {
                return inputCaptured || splitterOverlayOpen();
            }

            @Override
            public boolean layoutVisible() {
                return shellLayout.isProjectVisible();
            }

            @Override
            public void focusPanel() {
                focusProjectPanel();
            }

            @Override
            public void requestFocus() {
                requestProjectFocus();
            }

            @Override
            public void beginInput(InputMode mode) {
                beginProjectInput(mode);
            }

            @Override
            public void refreshTree() {
                refreshProjectTree();
            }

            @Override
            public void openFile(Path file) {
                VintelliShell.this.openFile(file, 0);
            }

            @Override
            public boolean pendingFocus() {
                return pendingProjectFocus;
            }

            @Override
            public void clearPendingFocus() {
                pendingProjectFocus = false;
            }

            @Override
            public void releaseSplitterDrag() {
                if (splitterDrag != null) {
                    finishSplitterDrag();
                }
            }

            @Override
            public EventResult splitterMouse(MouseEvent mouse) {
                return handleSplitterMouse(mouse);
            }

            @Override
            public void togglePanel() {
                VintelliShell.this.toggleProjectPanel();
            }

            @Override
            public void revealActiveFile() {
                VintelliShell.this.revealActiveFile();
            }
        };
    }

    private void discoverProjectFiles() {
        frameLock.lock();
        try {
            if (projects.filesLoading()) {
                refreshProjectTree();
            }
        } catch (Exception unavailable) {
            projects.markUnavailable(unavailable.getMessage());
        } finally {
            frameLock.unlock();
        }
    }

    public void refreshProjectTree() {
        refreshReadFileSummary();
        frameLock.lock();
        try {
            projects.refresh(true);
        } finally {
            frameLock.unlock();
        }
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

    /**
     * A caller is whichever thread the runner or the event router drives: the
     * returned element delegates every render and input call to the wrapped
     * one under the frame lock, so the render pass, the key handling of the
     * focused list and its click handling cannot ever overlap an external
     * frame or an inspect render.
     */
    private Element locked(Element delegate) {
        return new LockedElement(delegate, frameLock, null, false);
    }

    private Element centerPanel() {
        Column editorPane = Toolkit.column().id("editor-panel");
        editorPane.addClass("tool-panel");
        editorPane.focusable();
        editorPane.add(new EditorHeader("editor-panel",
                new PaneHeader("editor-panel", () -> "Editor", () -> themes, this::focusMark), editor, this::focusMark));
        editorPane.add(Toolkit.lazy(this::editorContent));
        editorPane.fill();
        paneSeparatorHorizontal = separator(PaneGapElement.HORIZONTAL_SEPARATOR);
        paneSeparatorHorizontal.length(bottomWindowsHidden() ? 0 : 1);
        centerPanel = Toolkit.column().id("editor-area");
        centerPanel.add(editorPane);
        centerPanel.add(paneSeparatorHorizontal);
        centerPanel.fill();
        return new LockedElement(centerPanel, frameLock, this, false);
    }

    private static Column separator(String id) {
        Column separator = Toolkit.column().id(id);
        separator.add(Spacer.fill());
        return separator.length(1);
    }

    String focusMark() {
        return fileIcons.mode() == IconMode.ASCII ? PaneHeader.FOCUS_MARK_ASCII : PaneHeader.FOCUS_MARK;
    }

    ShellThemes activeThemes() {
        return themes;
    }

    boolean isToolWindowFocused(String id) {
        return id.equals(focusedToolWindowId);
    }

    /**
     * The status bar entry of the shell frame. The file selection sync runs
     * here, on every frame, as it did before the status bar moved to
     * {@link StatusBar}: the project list is the source of the selected file.
     */
    private Element statusBarEntry() {
        syncFileSelection();
        return new StatusBarElement(statusBar::widgets);
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
            if (event instanceof MouseEvent mouse) {
                return handleMouse(mouse);
            }
            if (event instanceof KeyEvent && splitterDrag != null) {
                finishSplitterDrag();
            }
            return accept(event);
        } finally {
            actionSurface.set(previous);
            frameLock.unlock();
        }
    }

    private EventResult handleMouse(MouseEvent mouse) {
        KeymapParts parts = overlays();
        if (parts.commandPalette().isOpen()) {
            parts.commandPalette().handleMouse(mouse).ifPresent(action -> dispatch(parts, action.id()));
            return EventResult.HANDLED;
        }
        if (parts.searchModel() != null && parts.searchModel().isOpen()) {
            parts.searchOverlay().handleMouse(mouse).ifPresent(target -> openRevealed(target.file(), target.line()));
            return EventResult.HANDLED;
        }
        if (parts.keymapHelp().isOpen()) {
            if (mouse.isClick()) {
                parts.keymapHelp().close();
            }
            return EventResult.HANDLED;
        }
        if (mouse.isClick() && mouse.y() == 0 && toolbarClicked(mouse)) {
            return EventResult.HANDLED;
        }
        if (parts.menuPopup().isOpen()) {
            if (mouse.isClick()) {
                Optional<String> action = parts.menuPopup().click(mouse.x(), mouse.y());
                syncEditorMenuNavigation(parts);
                action.ifPresent(value -> dispatch(parts, value));
            }
            return EventResult.HANDLED;
        }
        if (mouse.isClick() && toolChromeClicked(mouse)) {
            return EventResult.HANDLED;
        }
        EventResult splitterResult = handleSplitterMouse(mouse);
        if (splitterResult == EventResult.HANDLED) {
            return splitterResult;
        }
        if (mouse.isClick() && projectPanel.clicked(mouse)) {
            return EventResult.HANDLED;
        }
        if (mouse.isClick() && editor != null) {
            int tab = editor.tabAt(mouse.x(), mouse.y());
            if (tab >= 0) {
                editor.selectTab(tab);
                editor.requestFocus();
                status = "Editor tab: " + editor.activeDocument().orElseThrow().file();
                return EventResult.HANDLED;
            }
            int segment = editor.navigationSegmentAt(mouse.x(), mouse.y());
            if (segment >= 0) {
                navigateToBreadcrumb(segment);
                return EventResult.HANDLED;
            }
        }
        if (editor != null && editor.handlePointer(mouse)) {
            if (mouse.isPress()) {
                inputCaptured = false;
                capturedWindow = null;
            }
            return EventResult.HANDLED;
        }
        for (ToolWindow.Anchor anchor : List.of(ToolWindow.Anchor.BOTTOM, ToolWindow.Anchor.RIGHT)) {
            ToolWindow activeWindow = activeToolWindow(anchor);
            Rect activeArea = anchor == ToolWindow.Anchor.BOTTOM ? activeToolWindowArea : activeRightToolWindowArea;
            if (activeWindow != null && contains(activeArea, mouse)) {
                String action = activeWindow.actionForMouse(mouse);
                if (action != null) {
                    focusToolWindow(activeWindow);
                    inputCaptured = activeWindow.capturesInput();
                    capturedWindow = inputCaptured ? activeWindow : null;
                    dispatch(parts, action);
                    return EventResult.HANDLED;
                }
                if (activeWindow.handleMouse(mouse)) {
                    focusToolWindow(activeWindow);
                    inputCaptured = activeWindow.capturesInput();
                    capturedWindow = inputCaptured ? activeWindow : null;
                    return EventResult.HANDLED;
                }
            }
        }
        return EventResult.UNHANDLED;
    }

    private static boolean contains(Rect area, MouseEvent mouse) {
        return mouse.x() >= area.x() && mouse.x() < area.x() + area.width()
                && mouse.y() >= area.y() && mouse.y() < area.y() + area.height();
    }

    void renderedToolWindowArea(ToolWindow window, Rect area) {
        if (window.anchor() == ToolWindow.Anchor.BOTTOM && window == activeBottomWindow()) {
            activeToolWindowArea = area;
        } else if (window.anchor() == ToolWindow.Anchor.RIGHT && window == activeToolWindow(ToolWindow.Anchor.RIGHT)) {
            activeRightToolWindowArea = area;
        }
    }

    /**
     * Captures only the rendered separator cells: the column between the
     * project and editor panes and the row between the center area and the
     * bottom tool windows. Ordinary clicks continue to the panel, tree, tabs
     * and tool windows.
     */
    private EventResult handleSplitterMouse(MouseEvent mouse) {
        if (splitterDrag != null) {
            if (mouse.isRelease()) {
                finishSplitterDrag();
                return EventResult.HANDLED;
            }
            if (mouse.kind() == MouseEventKind.DRAG && mouse.isLeftButton()) {
                resizeSplitter(mouse);
                return EventResult.HANDLED;
            }
            if (mouse.kind() == MouseEventKind.MOVE
                    || mouse.isPress()) {
                finishSplitterDrag();
            }
        }
        if (!mouse.isPress() || !mouse.isLeftButton() || splitterOverlayOpen()) {
            return EventResult.UNHANDLED;
        }
        Rect vertical = renderedAreaOrEmpty(paneSeparatorVertical);
        Rect horizontal = renderedAreaOrEmpty(paneSeparatorHorizontal);
        if (vertical.width() <= 0 || vertical.height() <= 0 || horizontal.width() <= 0 || horizontal.height() <= 0
                || shellArea.width() <= 0 || shellArea.height() <= 1) {
            return EventResult.UNHANDLED;
        }
        int right = horizontal.x() + horizontal.width() - 1;
        int bottom = vertical.y() + vertical.height() - 1;
        if (!bottomWindowsHidden() && mouse.y() == horizontal.y() && mouse.x() >= horizontal.x() && mouse.x() <= right) {
            splitterDrag = SplitterDrag.BOTTOM;
        } else if (shellLayout.isProjectVisible() && mouse.x() == vertical.x()
                && mouse.y() >= vertical.y() && mouse.y() <= bottom) {
            splitterDrag = SplitterDrag.PROJECT;
        } else {
            return EventResult.UNHANDLED;
        }
        splitterChanged = false;
        return EventResult.HANDLED;
    }

    private static Rect renderedAreaOrEmpty(Element element) {
        Rect area = element == null ? null : element.renderedArea();
        return area == null ? Rect.ZERO : area;
    }

    private boolean splitterOverlayOpen() {
        KeymapParts parts = overlays();
        return parts.menuPopup().isOpen() || parts.commandPalette().isOpen() || parts.keymapHelp().isOpen()
                || parts.searchModel() != null && parts.searchModel().isOpen();
    }

    private void resizeSplitter(MouseEvent mouse) {
        if (mouse.x() < 0 || mouse.y() < 0 || shellArea.width() <= 0 || shellArea.height() <= 1
                || centerPanel == null) {
            return;
        }
        Rect panel = centerPanel.renderedArea();
        if (panel.width() <= 0 || panel.height() <= 0 || layoutDock == null) {
            return;
        }
        if (splitterDrag == SplitterDrag.PROJECT) {
            if (projectBase() < ShellLayout.MIN_PROJECT_WIDTH + ShellLayout.MIN_EDITOR_WIDTH) {
                return;
            }
            adoptDefaultSizes();
            setProjectWidth(mouse.x() - shellArea.x() - TOOL_STRIP_WIDTH);
            int width = sizedProjectWidth();
            layoutDock.leftWidth(Constraint.length(leftRegionWidth(width)));
            splitterChanged = true;
            status = "Project panel: " + width + " columns";
        } else if (splitterDrag == SplitterDrag.BOTTOM) {
            int available = shellArea.height() - 1;
            if (available < ShellLayout.MIN_BOTTOM_HEIGHT + ShellLayout.MIN_EDITOR_HEIGHT) {
                return;
            }
            adoptDefaultSizes();
            int mainTop = shellArea.y() + 1;
            int editorHeight = mouse.y() - mainTop + 1;
            shellLayout.bottomHeight(available - editorHeight, available);
            int height = bottomPaneRows(shellLayout.bottomHeight(available));
            bottomDock.bottomHeight(Constraint.length(height));
            splitterChanged = true;
            status = "Tool window height: " + height + " rows";
        }
    }

    private void finishSplitterDrag() {
        if (splitterDrag != null && splitterChanged) {
            persistLayout();
        }
        splitterDrag = null;
        splitterChanged = false;
    }

    /** Ends an in-progress drag when its input surface stops delivering events. */
    public void cancelSplitterDrag() {
        frameLock.lock();
        try {
            finishSplitterDrag();
        } finally {
            frameLock.unlock();
        }
    }

    /**
     * Routes a click on the toolbar row to {@link ToolbarController}.
     *
     * @return true when the click landed on a toolbar segment the toolbar took
     */
    private boolean toolbarClicked(MouseEvent mouse) {
        return mainToolbar.segmentAt(mouse.x(), mouse.y()).map(toolbar::click).orElse(false);
    }

    private void showMenu(KeymapParts parts, int index) {
        parts.commandPalette().close();
        parts.keymapHelp().close();
        if (parts.searchModel() != null) {
            parts.searchModel().close();
        }
        openMenu(parts, index);
        inputCaptured = false;
    }

    private EventResult accept(Event event) {
        if (!(event instanceof KeyEvent key)) {
            return EventResult.UNHANDLED;
        }
        syncFileSelection();
        if (navigationBarFocused) {
            if (key.isKey(KeyCode.ESCAPE)) {
                navigationBarFocused = false;
                editor.requestFocus();
                return EventResult.HANDLED;
            }
            if (key.isKey(KeyCode.LEFT) || key.isKey(KeyCode.UP)) {
                selectedNavigationSegment = Math.max(0, selectedNavigationSegment - 1);
                return EventResult.HANDLED;
            }
            if (key.isKey(KeyCode.RIGHT) || key.isKey(KeyCode.DOWN)) {
                selectedNavigationSegment = Math.min(navigationPaths.size() - 1, selectedNavigationSegment + 1);
                return EventResult.HANDLED;
            }
            if (key.isKey(KeyCode.ENTER) && !navigationPaths.isEmpty()) {
                navigateToBreadcrumb(selectedNavigationSegment);
                return EventResult.HANDLED;
            }
        }
        if (projects.inputActive() && projectPanel.handleKey(key)) {
            return EventResult.HANDLED;
        }
        if (projects.deletePending()) {
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
        if (vcsActions.promptOpen()) {
            vcsActions.handleVcsPromptKey(promptKey(key));
            return EventResult.HANDLED;
        }
        if (vcsActions.writeInProgress()) {
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
            parts.searchOverlay().handleKey(key).ifPresent(target -> openRevealed(target.file(), target.line()));
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
        if (projectPanel.performAction(actionId)) {
            closeOverlays(parts);
            return;
        }
        switch (actionId) {
            case KeyActions.FOCUS_NAVIGATION_BAR -> {
                closeOverlays(parts);
                focusNavigationBar();
            }
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
            case KeyActions.IDEA_MCP_CONNECT -> {
                closeOverlays(parts);
                connectIdeaMcpAsync();
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
                vcsActions.refreshVcsAsync();
            }
            case KeyActions.VCS_SHOW_DIFF -> {
                closeOverlays(parts);
                vcsActions.showVcsDiff();
            }
            case KeyActions.VCS_DIFF_WORKING_TREE -> {
                closeOverlays(parts);
                vcsActions.showVcsWorkingTreeDiff();
            }
            case KeyActions.VCS_STAGE -> {
                closeOverlays(parts);
                vcsActions.requestStage(false);
            }
            case KeyActions.VCS_UNSTAGE -> {
                closeOverlays(parts);
                vcsActions.requestStage(true);
            }
            case KeyActions.VCS_COMMIT -> {
                closeOverlays(parts);
                vcsActions.requestCommit();
            }
            case KeyActions.VCS_SWITCH_BRANCH -> {
                closeOverlays(parts);
                vcsActions.requestBranchSwitch();
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
            case KeyActions.SHELL_SETTINGS -> {
                closeOverlays(parts);
                parts.commandPalette().openWith("theme");
                status = "Settings: pick the shell theme (icons: " + fileIcons.mode().name() + ", set with vintelli.icons)";
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
        projects.activateSelected().ifPresent(file -> openFile(file, 0));
    }

    private void focusProjectPanel() {
        inputCaptured = false;
        capturedWindow = null;
        focusedToolWindowId = null;
        navigationBarFocused = false;
        FocusManager manager = focusManager;
        if (manager != null) {
            manager.setFocus("project-panel");
        }
    }

    private void openRevealed(Path file, int line) {
        revealFile(file);
        openFile(file, line);
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
        vcsActions.refreshVcsAsync();
        status = editor.status();
    }

    private void saveActiveFile() {
        if (editor == null) {
            status = "Built-in editor unavailable";
            return;
        }
        editor.save();
        vcsActions.refreshVcsAsync();
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
            openRevealed(location.filePath(), location.line());
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
        if (projectBase() <= 0 || layoutDock == null) {
            status = "Project layout is not ready";
            return;
        }
        if (!shellLayout.isProjectVisible()) {
            status = "Project panel is hidden";
            return;
        }
        adoptDefaultSizes();
        setProjectWidth(projectPanel.renderedArea().width() + delta);
        layoutDock.leftWidth(Constraint.length(leftRegionWidth(sizedProjectWidth())));
        persistLayout();
        status = "Project panel: " + sizedProjectWidth() + " columns";
    }

    private void resizeBottom(int delta) {
        int available = availableHeight();
        if (available <= 0 || layoutDock == null) {
            status = "Tool window layout is not ready";
            return;
        }
        if (bottomWindowsHidden()) {
            status = "Bottom tool windows are hidden";
            return;
        }
        adoptDefaultSizes();
        shellLayout.bottomHeight(Math.max(0, available - centerPanel.renderedArea().height()), available);
        shellLayout.resizeBottom(delta, available);
        int height = bottomPaneRows(shellLayout.bottomHeight(available));
        bottomDock.bottomHeight(Constraint.length(height));
        persistLayout();
        status = "Tool window height: " + height + " rows";
    }

    /**
     * Turns the fixed default sizes into ratios just before the first resize, so
     * resizing one pane leaves the other at the size it has on screen.
     */
    private void adoptDefaultSizes() {
        if (!shellLayout.isSizeCustomized()) {
            shellLayout.projectWidth(PROJECT_PANEL_WIDTH, projectBase());
            shellLayout.bottomHeight(defaultBottomHeight(), availableHeight());
        }
    }

    private int defaultBottomHeight() {
        return hasBottomWindows() ? BOTTOM_HEIGHT : TOOL_WINDOW_HEIGHT + 2;
    }

    /**
     * The width the project ratio is measured against. It leaves out the left
     * strip only, so opening or closing a right window never moves the project
     * pane; the right region is taken off by {@link #projectCap(int)} instead.
     */
    private int projectBase() {
        if (shellArea.width() > 0) {
            return shellArea.width() - TOOL_STRIP_WIDTH;
        }
        Rect project = projectPanel.renderedArea();
        Rect center = centerPanel == null ? Rect.ZERO : centerPanel.renderedArea();
        return project.width() + center.width();
    }

    /** The widest project pane that still leaves the editor its minimum beside the open right region. */
    private int projectCap(int base) {
        return base - rightStripeWidth() - ShellLayout.MIN_EDITOR_WIDTH - PANE_SEPARATOR_WIDTH;
    }

    private int sizedProjectWidth() {
        int base = projectBase();
        return Math.min(shellLayout.projectWidth(base), Math.max(0, projectCap(base)));
    }

    private void setProjectWidth(int requestedCells) {
        int base = projectBase();
        shellLayout.projectWidth(Math.min(requestedCells, projectCap(base)), base);
    }

    private int rightStripeWidth() {
        if (windowsByAnchor.getOrDefault(ToolWindow.Anchor.RIGHT, List.of()).isEmpty()) {
            return 0;
        }
        int panelWidth = 0;
        if (!visibleWindows(ToolWindow.Anchor.RIGHT).isEmpty()) {
            ToolWindow active = activeToolWindow(ToolWindow.Anchor.RIGHT);
            panelWidth = active instanceof AcpAgentsToolWindow ? ACP_SIDE_PANEL_WIDTH : SIDE_PANEL_WIDTH;
        }
        return panelWidth + TOOL_STRIP_WIDTH;
    }

    private int availableHeight() {
        if (shellArea.height() > 1) {
            return shellArea.height() - 1;
        }
        Rect center = centerPanel == null ? Rect.ZERO : centerPanel.renderedArea();
        return center.height() + BOTTOM_HEIGHT;
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
                editor == null ? "Built-in editor unavailable" : projects.fileCount() == 0 ? "No project files found" : "");
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
        addMenu(entries, "Tools", KeyActions.IDEA_MCP_CONNECT, "Connect / Reconnect IntelliJ IDEA",
                ideaConnectInProgress.get() ? "IDEA MCP connection is already in progress" : "");
        String vcsUnavailable = vcsActions.unavailableReason();
        addMenu(entries, "VCS", KeyActions.VCS_REFRESH, "Refresh", vcsUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_SHOW_DIFF, "Show Diff", canEdit ? vcsUnavailable : editorUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_DIFF_WORKING_TREE, "Diff Working Tree", vcsUnavailable);
        String writeUnavailable = vcsActions.writeInProgress() ? "A Git operation is already running" : vcsUnavailable;
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

    public void refreshVcs() {
        vcsActions.refresh();
    }

    static Set<Integer> changedLines(String diffText) {
        return VcsActions.changedLines(diffText);
    }

    private static VcsActions.PromptKey promptKey(KeyEvent key) {
        boolean character = key.code() == KeyCode.CHAR;
        return new VcsActions.PromptKey(key.isKey(KeyCode.ESCAPE), key.isKey(KeyCode.ENTER),
                key.isKey(KeyCode.BACKSPACE), character ? key.codePoint() : -1,
                character && !key.hasCtrl() && !key.hasAlt() && !Character.isISOControl(key.codePoint()));
    }

    public void showVcsDiff() {
        vcsActions.showVcsDiff();
    }

    public void showVcsWorkingTreeDiff() {
        vcsActions.showVcsWorkingTreeDiff();
    }

    private static void addMenu(List<MenuEntry> entries, String menu, String action, String label, String reason) {
        entries.add(new MenuEntry(menu, action, label, reason));
    }

    private void connectIdeaMcpAsync() {
        if (ideaMcpConnection == null) {
            status = "IDEA MCP connection control is unavailable";
            return;
        }
        if (!ideaConnectInProgress.compareAndSet(false, true)) {
            status = "IDEA MCP connection is already in progress";
            return;
        }
        status = "IDEA MCP: connecting…";
        Thread.ofVirtual().start(() -> {
            IdeaMcpConnection.Snapshot result;
            try {
                result = ideaMcpConnection.reconnectFromUserAction();
            } catch (RuntimeException failure) {
                result = new IdeaMcpConnection.Snapshot("ERROR",
                        "connection failed; check the configured HTTP Stream Config and retry");
            }
            IdeaMcpConnection.Snapshot completed = result;
            Runnable update = () -> {
                ideaConnectInProgress.set(false);
                status = "IDEA MCP: " + completed.state().toLowerCase(Locale.ROOT).replace('_', ' ')
                        + (completed.detail().isBlank() ? "" : " — " + completed.detail());
                for (ToolWindow window : toolWindows) {
                    if ("run".equals(window.id())) {
                        window.performAction(KeyActions.RUN_REFRESH);
                    } else if ("problems".equals(window.id())) {
                        window.performAction(KeyActions.PROBLEMS_REFRESH);
                    }
                }
            };
            if (uiWorkQueue != null) {
                uiWorkQueue.execute(update);
            } else {
                frameLock.lock();
                try {
                    update.run();
                } finally {
                    frameLock.unlock();
                }
            }
        });
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

        int anchorIndex = visibleWindows(anchor).indexOf(window);
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
        return activeToolWindow(ToolWindow.Anchor.BOTTOM);
    }

    private ToolWindow activeToolWindow(ToolWindow.Anchor anchor) {
        List<ToolWindow> visible = visibleWindows(anchor);
        int index = activeToolIndexByAnchor.getOrDefault(anchor, 0);
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
    }

    private void focusToolWindow(ToolWindow window) {
        focusedToolWindowId = window.id();
        FocusManager manager = focusManager;
        if (manager != null) {
            manager.setFocus("tool-" + window.id());
        }
    }

    boolean isFocusedToolWindow(ToolWindow window) {
        return window == activeToolWindow(window.anchor()) && window.id().equals(focusedToolWindowId);
    }

    private static boolean isCommandAction(String actionId) {
        return KeyActions.RUN_EXECUTE.equals(actionId) || KeyActions.RUN_STOP.equals(actionId)
                || KeyActions.TERMINAL_EXECUTE.equals(actionId)
                || KeyActions.ACP_CHAT_NEW.equals(actionId) || KeyActions.ACP_CHAT_SEND.equals(actionId)
                || KeyActions.ACP_CHAT_CANCEL.equals(actionId) || KeyActions.ACP_CHAT_PERMISSION.equals(actionId)
                || KeyActions.ACP_CHAT_CLOSE.equals(actionId) || KeyActions.ACP_CHAT_SELECT_TAB.equals(actionId);
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
        projects.moveSelection(delta);
    }

    private void syncFileSelection() {
        projects.syncSelection();
    }

    private void releaseCapture() {
        inputCaptured = false;
        capturedWindow = null;
        focusedToolWindowId = null;
        pendingProjectFocus = shellLayout.isProjectVisible();
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
            updateNavigationBar();
            return editor.element();
        }
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(EDITOR_TEXT));
        RichTextElement content = Toolkit.richText(Text.from(lines));
        content.id("editor-content");
        content.addClass("editor-content");
        return content;
    }

    private void updateNavigationBar() {
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
                NavigationCapability provider = availableNavigationProvider();
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
            editor.navigationBar(paths.stream().map(StatusBar.Breadcrumb::label).toList(), selectedNavigationSegment);
        } catch (IllegalArgumentException outsideProject) {
            navigationPaths = List.of();
            editor.navigationBar(List.of(), 0);
        }
    }

    private void focusNavigationBar() {
        updateNavigationBar();
        if (navigationPaths.isEmpty()) {
            status = "Open a project file to navigate its path";
            return;
        }
        navigationBarFocused = true;
        focusedToolWindowId = null;
        inputCaptured = false;
        FocusManager manager = focusManager;
        if (manager != null) {
            manager.setFocus("editor-navigation-bar");
        }
        status = "Navigation bar: arrows move, Enter opens, Escape returns to editor";
    }

    private void navigateToBreadcrumb(int segment) {
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
            refreshProjectTree();
            projects.selectPath(relative.toString().replace('\\', '/'));
            requestProjectFocus();
        } else {
            revealFile(path);
            requestProjectFocus();
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
        paint(frame, null);
    }

    /**
     * Draws one frame as {@link #paint(Frame)} does, with {@code focusedId} focused
     * when it is not null: the offscreen frame has no key routing, so this is how
     * a caller sees the focus-dependent parts of the frame, such as the editor caret.
     */
    public void paint(Frame frame, String focusedId) {
        frameLock.lock();
        try {
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(themes.engine());
            if (focusedId != null) {
                context.focusManager().setFocus(focusedId);
            }
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
        projects.resetSync();
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
        return projects.selected();
    }

    public List<String> projectFiles() {
        return projects.files();
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path selectedPath() {
        return projects.selectedPath().orElse(null);
    }

    public String selectedPathString() {
        return projects.selectedRelative().orElse("");
    }

    public void expandSelected() {
        projects.expandSelected();
    }

    public void collapseSelected() {
        projects.collapseSelected();
    }

    public void toggleSelected() {
        projects.toggleSelected();
    }

    private void beginProjectInput(InputMode mode) {
        if (projects.beginInput(mode)) {
            requestProjectFocus();
        }
    }

    public boolean createFile(String name) {
        return projects.createFile(name);
    }

    public boolean createDirectory(String name) {
        return projects.createDirectory(name);
    }

    public boolean renameSelected(String newName) {
        return projects.renameSelected(newName);
    }

    public void requestDeleteSelected() {
        projects.requestDelete();
    }

    public boolean deleteSelectedConfirmed() {
        return projects.confirmDelete();
    }

    public void confirmDelete() {
        deleteSelectedConfirmed();
    }

    public void cancelDelete() {
        projects.cancelDelete();
    }

    public boolean isDeletePending() {
        return projects.deletePending();
    }

    public void copySelectedPath() {
        projects.copySelectedPath();
    }

    public Path copiedPath() {
        return projects.copiedPath();
    }

    public void revealFile(Path file) {
        if (file == null) {
            return;
        }
        String relative = projects.expandToFile(file);
        if (relative != null) {
            refreshProjectTree();
            projects.selectPath(relative);
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
        setShowHiddenFiles(!projects.showHidden());
    }

    public void setShowHiddenFiles(boolean value) {
        projects.setShowHidden(value);
        if (projectSearch != null) {
            refreshProjectTree();
        }
    }

    public boolean showHiddenFiles() {
        return projects.showHidden();
    }

    public void selectPath(String relative) {
        projects.selectPath(relative);
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
    private record LockedElement(Element delegate, ReentrantLock lock, VintelliShell projectOwner,
            boolean shellRoot) implements Element {

        @Override
        public void render(Frame frame, Rect area, RenderContext context) {
            lock.lock();
            try {
                if (shellRoot && projectOwner != null) {
                    projectOwner.shellArea = area;
                    if (context instanceof DefaultRenderContext drc) {
                        projectOwner.focusManager = drc.focusManager();
                    }
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
                if (projectOwner != null && projectOwner.splitterDrag != null) {
                    projectOwner.finishSplitterDrag();
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
                if (projectOwner != null && projectOwner.handleSplitterMouse(event) == EventResult.HANDLED) {
                    return EventResult.HANDLED;
                }
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
