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
import dev.omatheusmesmo.vintelli.tui.editor.GoToLine;
import dev.omatheusmesmo.vintelli.tui.editor.GoToLineOverlay;
import dev.omatheusmesmo.vintelli.tui.menu.MenuPopup;
import dev.omatheusmesmo.vintelli.tui.OverlayStack.Kind;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayoutStore;
import dev.omatheusmesmo.vintelli.tui.maven.MavenGoal;
import dev.omatheusmesmo.vintelli.tui.maven.MavenToolWindow;
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
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidgetContributor;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.elements.DockElement;
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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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

    static final int PROJECT_PANEL_WIDTH = PaneLayoutController.PROJECT_PANEL_WIDTH;
    static final int TOOL_STRIP_WIDTH = PaneLayoutController.TOOL_STRIP_WIDTH;
    private static final int PANE_SEPARATOR_WIDTH = PaneLayoutController.PANE_SEPARATOR_WIDTH;

    private static final List<String> MENUS = List.of("File", "Edit", "View", "Navigate", "Code", "Refactor", "Run", "Tools", "VCS", "Help");

    /**
     * The keymap-driven parts of the shell, built together the first time the
     * TUI session actually uses them.
     */
    private record KeymapParts(Keymap keymap, CommandPalette commandPalette, KeymapHelpOverlay keymapHelp,
            MenuPopup menuPopup, ProjectSearchModel searchModel, ProjectSearchOverlay searchOverlay,
            OverlayStack stack, RunConfigurationChooser runConfigurations, GoToLineOverlay goToLine) {
    }

    private final ShellThemes themes;
    private final ShellState state;
    private final List<ToolWindow> toolWindows;
    private final BuiltinEditor editor;
    private final ProjectSearch projectSearch;
    private final List<NavigationCapability> navigationProviders;
    private final CapabilityRegistry capabilityRegistry;
    private final ReadFileSummary readFileSummary;
    private final MainToolbar mainToolbar;
    private final ToolbarController toolbar;
    private final RunToolWindow runWindow;
    private final IdeaConnection ideaConnection;
    private final ToolStripes toolStripes;
    private final StatusBar statusBar;
    private final StatusBarElement statusBarElement;
    private final ProjectFileOperations fileOperations;
    private final ShellLayout shellLayout;
    private final PaneLayoutController paneLayout;
    private volatile DockElement layoutDock;
    private volatile DockElement bottomDock;
    private volatile FocusManager focusManager;
    private final ToolWindowHost toolWindowHost;
    private final ToolWindowPanel toolWindowPanel;
    private Rect shellArea = Rect.ZERO;
    private final ReentrantLock frameLock = new ReentrantLock();
    private final Supplier<Keymap> keymapSupplier;
    private final StackElement rootBase;
    private final Element lockedRoot;
    private final ProjectTreeController projects;
    private final List<MavenToolWindow> mavenWindows;
    private final ShellTicker ticker;
    private final VcsActions vcsActions;
    private final ProjectPanel projectPanel;
    private final EditorController editorController;
    private final EditorPanel editorPanel;
    private Column centerPanel;
    private Column paneSeparatorVertical;
    private Column paneSeparatorHorizontal;
    private final Path projectRoot;
    private final FileIcons fileIcons;
    private boolean pendingProjectFocus;
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

    private volatile String status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
    private volatile boolean inputCaptured;
    private volatile ToolWindow capturedWindow;
    private volatile int toolIndex;

    void useUiWorkQueue(UiWorkQueue uiWorkQueue) {
        this.uiWorkQueue = uiWorkQueue;
    }

    void useClock(LongSupplier clock) {
        projects.useClock(clock);
    }

    void useUnicodeProjectTree(boolean unicode) {
        projectPanel.useUnicodeTree(unicode);
    }

    @Inject
    public VintelliShell(ShellConfig config) {
        List<ToolWindow> toolWindows = config.toolWindows();
        ShellState state = config.state();
        Supplier<Keymap> keymapSupplier = config.keymapSupplier();
        ShellThemes themes = config.themes();
        ProjectSearch projectSearch = config.projectSearch();
        List<NavigationCapability> navigationProviders = config.navigationProviders();
        CapabilityRegistry capabilityRegistry = config.capabilityRegistry();
        List<VersionControlCapability> vcsProviders = config.vcsProviders();
        boolean persistLayout = config.persistLayout();
        List<? extends StatusBarWidgetContributor> statusBarContributors = config.statusBarContributors();
        ProjectFileOperations fileOperations = config.fileOperations();
        IdeaMcpConnection ideaMcpConnection = config.ideaMcpConnection();
        ShellLayoutStore layoutStoreOverride = config.layoutStore();
        this.themes = themes;
        this.state = state;
        this.projectSearch = projectSearch;
        this.fileOperations = fileOperations;
        this.navigationProviders = List.copyOf(navigationProviders);
        this.capabilityRegistry = capabilityRegistry;
        this.readFileSummary = new ReadFileSummary(capabilityRegistry);
        this.ideaConnection = new IdeaConnection(ideaMcpConnection, ideaHost());
        this.editor = toolWindows.stream().filter(BuiltinEditor.class::isInstance)
                .map(BuiltinEditor.class::cast).findFirst().orElse(null);
        this.toolWindows = toolWindows.stream().filter(window -> !(window instanceof BuiltinEditor)).toList();
        this.toolWindows.stream().filter(StructureToolWindow.class::isInstance)
                .map(StructureToolWindow.class::cast)
                .forEach(window -> window.setNavigateHandler(this::openStructureSymbol));
        this.toolWindows.stream().filter(ProblemsToolWindow.class::isInstance)
                .map(ProblemsToolWindow.class::cast)
                .forEach(window -> window.setNavigateHandler(this::openDiagnostic));
        this.toolWindows.stream().filter(MavenToolWindow.class::isInstance)
                .map(MavenToolWindow.class::cast)
                .forEach(window -> window.setRunHandler(this::runMavenGoal));
        this.mavenWindows = this.toolWindows.stream().filter(MavenToolWindow.class::isInstance)
                .map(MavenToolWindow.class::cast)
                .toList();
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
        this.projects.useMarquee(config.marquee());
        this.mavenWindows.forEach(window -> window.useMarquee(config.marquee()));
        this.ticker = new ShellTicker(Marquee.STEP, frameLock, this::animating, this::advanceAnimations);
        this.projects.onMarqueeChange(ticker::sync);
        this.mavenWindows.forEach(window -> window.onMarqueeChange(ticker::sync));
        this.vcsActions = new VcsActions(vcsProviders, frameLock, message -> status = message, this::show,
                projects, editor);
        this.editorController = new EditorController(this.projectRoot, editor, projects, editorHost());
        this.editorPanel = new EditorPanel(editorController, editor, themes, () -> overlays().keymap(), this.projectRoot);
        this.fileIcons = FileIcons.resolve();
        Path projectRoot = editor == null ? Path.of("").toAbsolutePath().normalize() : editor.project();
        Path configRoot = persistLayout ? ConfigDirectory.configRoot(ConfigDirectory.sharedConfigDir(),
                System.getenv("XDG_CONFIG_HOME"), System.getenv("APPDATA"), System.getProperty("user.home")) : null;
        ShellLayoutStore layoutStore = layoutStoreOverride == null ? new ShellLayoutStore(configRoot, projectRoot)
                : layoutStoreOverride;
        Set<String> allWindowIds = this.toolWindows.stream().map(ToolWindow::id).collect(Collectors.toSet());
        Set<String> defaultVisibleWindowIds = this.toolWindows.stream()
                .filter(window -> window.anchor() != ToolWindow.Anchor.RIGHT)
                .map(ToolWindow::id)
                .collect(Collectors.toSet());
        this.shellLayout = layoutStore.load(allWindowIds, defaultVisibleWindowIds);
        this.toolWindowHost = new ToolWindowHost(this.toolWindows, shellLayout, toolWindowShellHost());
        this.paneLayout = new PaneLayoutController(shellLayout, layoutStore, paneLayoutHost());
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
        this.toolWindowPanel = new ToolWindowPanel(toolWindowHost, fileIcons.mode(), () -> themes, this::focusMark,
                toolWindowElementHost());
        this.toolStripes = new ToolStripes(toolWindowHost.windowsByAnchor(), fileIcons.mode(), stripHost());
        this.statusBar = new StatusBar(this::statusLine, () -> projects.inputActive() || vcsActions.promptOpen()
                || projects.deletePending(), capabilityRegistry, editor, statusBarContributors,
                new StatusBar.Glyphs(ProjectTreeGlyphs.supportsUnicode(), fileIcons.mode()),
                editorController::statusNavigation, config.ideaStatus());
        this.statusBarElement = new StatusBarElement(statusBar, editorController::navigateToBreadcrumb);
        this.rootBase = buildRootContent();
        this.lockedRoot = new LockedElement(rootBase, frameLock, this, true);
        if (projectSearch != null) {
            Thread.ofVirtual().start(projectPanel::discoverFiles);
        }
        readFileSummary.refresh();
        vcsActions.refreshToolbarVcs();
        restoreSessionThenRefreshProblems();
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
                    overlays = buildOverlays();
                }
                parts = overlays;
            }
        }
        return parts;
    }

    private KeymapParts buildOverlays() {
        Keymap keymap = keymapSupplier.get();
        ProjectSearchModel searchModel = projectSearch == null ? null : new ProjectSearchModel(projectSearch);
        ShellMenuCatalog menuCatalog = new ShellMenuCatalog(menuCatalogHost());
        CommandPalette commandPalette = new CommandPalette(keymap.actions(), keymap);
        KeymapHelpOverlay keymapHelp = new KeymapHelpOverlay(keymap);
        MenuPopup menuPopup = new MenuPopup(MENUS, menuCatalog::entries, keymap::describeBindings,
                MainToolbar.MENU_ORIGIN);
        ProjectSearchOverlay searchOverlay = searchModel == null ? null : new ProjectSearchOverlay(searchModel);
        OverlayStack stack = new OverlayStack();
        GoToLineOverlay goToLine = new GoToLineOverlay();
        RunConfigurationChooser runConfigurations = new RunConfigurationChooser(
                () -> runWindow == null ? List.of() : runWindow.configurations());
        KeymapParts built = new KeymapParts(keymap, commandPalette, keymapHelp, menuPopup, searchModel,
                searchOverlay, stack, runConfigurations, goToLine);
        stack.registerOverlay(Kind.COMMAND_PALETTE, commandPalette::isOpen, commandPalette::close);
        stack.registerOverlay(Kind.KEYMAP_HELP, keymapHelp::isOpen, keymapHelp::close);
        stack.registerOverlay(Kind.MENU, menuPopup::isOpen, () -> closeMenu(built));
        stack.registerOverlay(Kind.RUN_CONFIGURATION, runConfigurations::isOpen, runConfigurations::close);
        stack.registerOverlay(Kind.GO_TO_LINE, goToLine::isOpen, () -> closeGoToLine(goToLine));
        if (searchModel != null) {
            stack.registerOverlay(Kind.PROJECT_SEARCH, searchModel::isOpen, searchModel::close);
        }
        stack.registerPrompt(Kind.PROJECT_DELETE, projects::deletePending);
        if (editor != null) {
            stack.registerPrompt(Kind.EDITOR_CLOSE, editor::closeConfirmationPending);
        }
        stack.registerPrompt(Kind.VCS_PROMPT, vcsActions::promptOpen);
        return built;
    }

    private ShellMenuCatalog.Host menuCatalogHost() {
        return new ShellMenuCatalog.Host() {
            @Override
            public boolean editorAvailable() {
                return editor != null;
            }

            @Override
            public boolean documentOpen() {
                return editor != null && editor.activeDocument().isPresent();
            }

            @Override
            public boolean dirtyEditorTab() {
                return editor != null && editor.tabs().stream().anyMatch(BuiltinEditor.TabView::dirty);
            }

            @Override
            public int projectFileCount() {
                return projects.fileCount();
            }

            @Override
            public boolean projectSearchAvailable() {
                return projectSearch != null;
            }

            @Override
            public String navigationUnavailableReason() {
                if (availableNavigationProvider() != null) {
                    return "";
                }
                return navigationProviders.isEmpty() ? "Navigation provider unavailable"
                        : navigationProviders.stream().map(provider -> provider.health().reason())
                                .filter(Objects::nonNull).findFirst().orElse("Navigation provider unavailable");
            }

            @Override
            public List<ToolWindow> toolWindows() {
                return toolWindows;
            }

            @Override
            public boolean ideaConnectInProgress() {
                return ideaConnection.inProgress();
            }

            @Override
            public String vcsUnavailableReason() {
                return vcsActions.unavailableReason();
            }

            @Override
            public boolean vcsWriteInProgress() {
                return vcsActions.writeInProgress();
            }
        };
    }

    private StackElement buildRootContent() {
        StackElement stack = Toolkit.stack();
        stack.add(Toolkit.lazy(this::shellLayout));
        stack.add(Toolkit.lazy(this::overlayElements));
        return stack;
    }

    private Element shellLayout() {
        if (uiWorkQueue != null) {
            uiWorkQueue.drain();
        }
        ticker.sync();
        boolean customized = paneLayout.isSizeCustomized();
        Constraint projectWidth = Constraint.length(paneLayout.leftRegionWidth(customized
                ? paneLayout.sizedProjectWidth() : PROJECT_PANEL_WIDTH));
        DockElement editorDock = Toolkit.dock()
                .center(centerPanel())
                .bottom(toolWindowPanel.bottomStripe())
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
                .rightWidth(Constraint.length(paneLayout.rightStripeWidth()))
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
        return Constraint.length(toolWindowHost.bottomPaneRows(customized, paneLayout.availableHeight()));
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

    private ToolWindowHost.Host toolWindowShellHost() {
        return new ToolWindowHost.Host() {
            @Override
            public void persistLayout() {
                VintelliShell.this.persistLayout();
            }

            @Override
            public void moveFocus(String focusId) {
                FocusManager manager = focusManager;
                if (manager != null) {
                    manager.setFocus(focusId);
                }
            }

            @Override
            public void windowHidden(ToolWindow window) {
                if (capturedWindow == window) {
                    inputCaptured = false;
                    capturedWindow = null;
                }
            }

            @Override
            public void status(String message) {
                VintelliShell.this.status = message;
            }
        };
    }

    private PaneLayoutController.Host paneLayoutHost() {
        return new PaneLayoutController.Host() {
            @Override
            public PaneLayoutController.Area viewport() {
                return plain(shellArea);
            }

            @Override
            public PaneLayoutController.Area verticalSeparator() {
                return plain(renderedAreaOrEmpty(paneSeparatorVertical));
            }

            @Override
            public PaneLayoutController.Area horizontalSeparator() {
                return plain(renderedAreaOrEmpty(paneSeparatorHorizontal));
            }

            @Override
            public int projectPanelWidth() {
                return projectPanel.renderedArea().width();
            }

            @Override
            public int centerWidth() {
                return centerArea().width();
            }

            @Override
            public int centerHeight() {
                return centerArea().height();
            }

            @Override
            public boolean layoutReady() {
                return layoutDock != null;
            }

            @Override
            public boolean bottomWindowsHidden() {
                return toolWindowHost.bottomWindowsHidden();
            }

            @Override
            public int defaultBottomHeight() {
                return toolWindowHost.defaultBottomHeight();
            }

            @Override
            public int bottomRows(int availableHeight) {
                return toolWindowHost.bottomRows(availableHeight);
            }

            @Override
            public boolean leftWindowsOpen() {
                return !toolWindowHost.visibleWindows(ToolWindow.Anchor.LEFT).isEmpty();
            }

            @Override
            public boolean rightRegistered() {
                return toolWindowHost.hasWindows(ToolWindow.Anchor.RIGHT);
            }

            @Override
            public boolean rightVisible() {
                return !toolWindowHost.visibleWindows(ToolWindow.Anchor.RIGHT).isEmpty();
            }

            @Override
            public boolean activeRightIsAgents() {
                return activeToolWindow(ToolWindow.Anchor.RIGHT) instanceof AcpAgentsToolWindow;
            }

            @Override
            public boolean overlayOpen() {
                return overlays().stack().overlayOpen();
            }

            @Override
            public void placeProject(int leftRegionWidth) {
                layoutDock.leftWidth(Constraint.length(leftRegionWidth));
            }

            @Override
            public void placeBottom(int rows) {
                bottomDock.bottomHeight(Constraint.length(rows));
            }

            @Override
            public void status(String message) {
                VintelliShell.this.status = message;
            }
        };
    }

    private ToolWindowElement.Host toolWindowElementHost() {
        return new ToolWindowElement.Host() {
            @Override
            public boolean isActive(ToolWindow window) {
                return toolWindowHost.isActive(window);
            }

            @Override
            public void rendered(ToolWindow window, Rect area) {
                toolWindowPanel.rendered(window, area);
            }

            @Override
            public EventResult handle(Event event) {
                return VintelliShell.this.handle(event, ActionSurface.TUI);
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
                return toolWindowHost.isVisible(id);
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
        boolean columnShown = paneLayout.leftColumnShown();
        if (columnShown) {
            left.add(leftPanel());
        } else {
            toolWindowPanel.forgetTabs(ToolWindow.Anchor.LEFT);
        }
        paneSeparatorVertical = separator("pane-separator-vertical");
        paneSeparatorVertical.length(columnShown ? PANE_SEPARATOR_WIDTH : 0);
        left.add(paneSeparatorVertical);
        left.fill();
        return left;
    }

    private Element leftPanel() {
        List<ToolWindow> leftWindows = toolWindowHost.visibleWindows(ToolWindow.Anchor.LEFT);
        if (leftWindows.isEmpty()) {
            toolWindowPanel.forgetTabs(ToolWindow.Anchor.LEFT);
            return projectPane();
        }
        Column column = Toolkit.column().id("left-panel");
        if (shellLayout.isProjectVisible()) {
            column.add(projectPane());
        }
        column.add(toolWindowPanel.stripe(ToolWindow.Anchor.LEFT));
        column.fill();
        return column;
    }

    private Element rightStripe() {
        ToolStripElement rightStrip = toolStripes.rightElement();
        if (rightStrip == null) {
            return null;
        }
        Row stripe = Toolkit.row().id("right-stripe");
        if (!toolWindowHost.visibleWindows(ToolWindow.Anchor.RIGHT).isEmpty()) {
            Column column = Toolkit.column().id("right-tool-stripe");
            column.add(toolWindowPanel.stripe(ToolWindow.Anchor.RIGHT));
            column.fill();
            stripe.add(separator("pane-separator-right"));
            stripe.add(column);
        }
        stripe.add(rightStrip);
        stripe.fill();
        return stripe;
    }

    public boolean isToolWindowVisible(String id) {
        return toolWindowHost.isVisible(id);
    }

    public void toggleToolWindow(String id) {
        toolWindowHost.toggle(id).ifPresent(window -> switchToToolWindow(toolWindows.indexOf(window)));
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
        Optional<ToolWindow> tab = toolWindowPanel.tabAt(mouse.x(), mouse.y());
        if (tab.isPresent()) {
            switchToToolWindow(toolWindows.indexOf(tab.get()));
            return true;
        }
        return false;
    }

    public void showToolWindow(String id) {
        toolWindowHost.show(id);
    }

    public void hideToolWindow(String id) {
        toolWindowHost.hide(id);
    }

    private Element projectPane() {
        Column pane = Toolkit.column().id("project-pane");
        pane.add(projectPanel.header(() -> themes, this::focusMark));
        pane.add(projectPanel);
        pane.add(projectPanel.footer(() -> themes, readFileSummary));
        pane.fill();
        return pane;
    }

    private ProjectPanel.Host projectHost() {
        return new ProjectPanel.Host() {
            @Override
            public Keymap keymap() {
                return overlays().keymap();
            }

            @Override
            public boolean shortcutsBlocked() {
                return inputCaptured || overlays().stack().overlayOpen();
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
                editorController.openFile(file, 0);
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
                paneLayout.cancel();
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

    private EditorController.Host editorHost() {
        return new EditorController.Host() {
            @Override
            public void status(String message) {
                status = message;
            }

            @Override
            public NavigationCapability navigationProvider() {
                return availableNavigationProvider();
            }

            @Override
            public void refreshVcs() {
                vcsActions.refreshVcsAsync();
            }

            @Override
            public void refreshDiagnostics() {
                VintelliShell.this.refreshProblems();
            }

            @Override
            public void refreshProjectTree() {
                VintelliShell.this.refreshProjectTree();
            }

            @Override
            public void requestProjectFocus() {
                VintelliShell.this.requestProjectFocus();
            }

            @Override
            public void releaseCapture() {
                VintelliShell.this.releaseCapture();
            }

            @Override
            public void takeFocus(String elementId) {
                inputCaptured = false;
                toolWindowHost.clearFocus();
                FocusManager manager = focusManager;
                if (manager != null) {
                    manager.setFocus(elementId);
                }
            }
        };
    }

    private void openStructureSymbol(NavigationCapability.DocumentSymbol symbol) {
        editorController.openActiveFileLine(symbol.selectionStartLine());
    }

    private void runMavenGoal(MavenGoal goal) {
        if (runWindow == null) {
            status = "Run window unavailable";
            return;
        }
        switchToToolWindow(toolWindows.indexOf(runWindow));
        runWindow.startGoal(goal.identifier(), goal.arguments());
    }

    private void openDiagnostic(Diagnostic problem) {
        editorController.openDiagnostic(problem);
    }

    public void refreshProjectTree() {
        readFileSummary.refresh();
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
        editorPane.add(Toolkit.lazy(editorPanel::content));
        editorPane.fill();
        paneSeparatorHorizontal = separator(PaneGapElement.HORIZONTAL_SEPARATOR);
        paneSeparatorHorizontal.length(toolWindowHost.bottomWindowsHidden() ? 0 : 1);
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

    /**
     * The status bar entry of the shell frame. The file selection sync runs
     * here, on every frame, as it did before the status bar moved to
     * {@link StatusBar}: the project list is the source of the selected file.
     */
    private Element statusBarEntry() {
        syncFileSelection();
        editorController.refreshNavigation();
        return statusBarElement;
    }

    private Element overlayElements() {
        KeymapParts parts = overlays();
        List<Kind> open = parts.stack().openOverlays();
        if (open.isEmpty()) {
            return null;
        }
        StackElement layers = Toolkit.stack();
        for (Kind kind : open) {
            layers.add(overlayElement(parts, kind));
        }
        return layers;
    }

    private Element overlayElement(KeymapParts parts, Kind kind) {
        return switch (kind) {
            case COMMAND_PALETTE -> new OverlayElement("command-palette", parts.commandPalette()::render);
            case KEYMAP_HELP -> new OverlayElement("keymap-help", parts.keymapHelp()::render);
            case MENU -> new OverlayElement("menu-popup", parts.menuPopup()::render);
            case RUN_CONFIGURATION -> new OverlayElement(RunConfigurationChooser.ID,
                    (frame, area) -> parts.runConfigurations().render(frame, area, mainToolbar.configurationColumn()));
            case PROJECT_SEARCH -> new OverlayElement("project-search", parts.searchOverlay()::render);
            case GO_TO_LINE -> new OverlayElement("go-to-line", parts.goToLine()::render);
            default -> throw new IllegalStateException("Not a drawn overlay: " + kind);
        };
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
            if (event instanceof KeyEvent && paneLayout.dragging()) {
                paneLayout.cancel();
            }
            return accept(event);
        } finally {
            actionSurface.set(previous);
            frameLock.unlock();
        }
    }

    private EventResult handleMouse(MouseEvent mouse) {
        KeymapParts parts = overlays();
        Kind top = parts.stack().topOverlay().orElse(null);
        if (top == Kind.COMMAND_PALETTE) {
            parts.commandPalette().handleMouse(mouse).ifPresent(action -> dispatch(parts, action.id()));
            return EventResult.HANDLED;
        }
        if (top == Kind.PROJECT_SEARCH) {
            parts.searchOverlay().handleMouse(mouse).ifPresent(target -> editorController.openRevealed(target.file(), target.line()));
            return EventResult.HANDLED;
        }
        if (top == Kind.KEYMAP_HELP) {
            if (mouse.isClick()) {
                parts.stack().close(Kind.KEYMAP_HELP);
            }
            return EventResult.HANDLED;
        }
        if (top == Kind.GO_TO_LINE) {
            return EventResult.HANDLED;
        }
        if (top == Kind.RUN_CONFIGURATION) {
            if (mouse.isClick()) parts.runConfigurations().click(mouse.x(), mouse.y())
                    .ifPresent(identifier -> selectRunConfiguration(parts, identifier));
            return EventResult.HANDLED;
        }
        if (mouse.isClick() && mouse.y() == 0 && toolbarClicked(mouse)) {
            return EventResult.HANDLED;
        }
        if (top == Kind.MENU) {
            if (mouse.isClick()) {
                Optional<String> action = parts.menuPopup().click(mouse.x(), mouse.y());
                syncEditorMenuNavigation(parts);
                action.ifPresent(value -> dispatch(parts, value));
            }
            return EventResult.HANDLED;
        }
        if (mouse.isClick() && statusBarElement.click(mouse.x(), mouse.y())) {
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
        if (mouse.isClick() && editorPanel.clicked(mouse)) {
            return EventResult.HANDLED;
        }
        if (editorPanel.pointer(mouse)) {
            if (mouse.isPress()) {
                inputCaptured = false;
                capturedWindow = null;
            }
            return EventResult.HANDLED;
        }
        for (ToolWindow.Anchor anchor : List.of(ToolWindow.Anchor.BOTTOM, ToolWindow.Anchor.RIGHT)) {
            ToolWindow activeWindow = activeToolWindow(anchor);
            Rect activeArea = toolWindowPanel.activeArea(anchor);
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

    /**
     * Captures only the rendered separator cells: the column between the
     * project and editor panes and the row between the center area and the
     * bottom tool windows. Ordinary clicks continue to the panel, tree, tabs
     * and tool windows.
     */
    private EventResult handleSplitterMouse(MouseEvent mouse) {
        if (mouse.isRelease()) {
            return splitterResult(paneLayout.release());
        }
        if (mouse.kind() == MouseEventKind.DRAG && mouse.isLeftButton()) {
            return splitterResult(paneLayout.drag(mouse.x(), mouse.y()));
        }
        if (mouse.kind() == MouseEventKind.MOVE) {
            paneLayout.move();
            return EventResult.UNHANDLED;
        }
        if (mouse.isPress()) {
            return splitterResult(paneLayout.press(mouse.x(), mouse.y(), mouse.isLeftButton()));
        }
        return EventResult.UNHANDLED;
    }

    private static EventResult splitterResult(boolean handled) {
        return handled ? EventResult.HANDLED : EventResult.UNHANDLED;
    }

    private static Rect renderedAreaOrEmpty(Element element) {
        Rect area = element == null ? null : element.renderedArea();
        return area == null ? Rect.ZERO : area;
    }

    private static PaneLayoutController.Area plain(Rect area) {
        return new PaneLayoutController.Area(area.x(), area.y(), area.width(), area.height());
    }

    private Rect centerArea() {
        return centerPanel == null ? Rect.ZERO : centerPanel.renderedArea();
    }

    /** Ends an in-progress drag when its input surface stops delivering events. */
    public void cancelSplitterDrag() {
        frameLock.lock();
        try {
            paneLayout.cancel();
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
        parts.stack().open(Kind.MENU, () -> openMenu(parts, index));
        inputCaptured = false;
    }

    private void handleOverlayKey(KeymapParts parts, Kind overlay, KeyEvent key) {
        switch (overlay) {
            case COMMAND_PALETTE -> parts.commandPalette().handleKey(key).ifPresent(action -> dispatch(parts, action.id()));
            case KEYMAP_HELP -> parts.keymapHelp().handleKey(key);
            case MENU -> {
                Optional<String> action = parts.menuPopup().handleKey(key);
                syncEditorMenuNavigation(parts);
                action.ifPresent(value -> dispatch(parts, value));
            }
            case RUN_CONFIGURATION -> parts.runConfigurations().handleKey(key)
                    .ifPresent(identifier -> selectRunConfiguration(parts, identifier));
            case PROJECT_SEARCH -> parts.searchOverlay().handleKey(key)
                    .ifPresent(target -> editorController.openRevealed(target.file(), target.line()));
            case GO_TO_LINE -> handleGoToLineKey(parts, key);
            default -> throw new IllegalStateException("Not a drawn overlay: " + overlay);
        }
    }

    private EventResult accept(Event event) {
        if (!(event instanceof KeyEvent key)) {
            return EventResult.UNHANDLED;
        }
        syncFileSelection();
        if (statusBarElement.escape(key)) {
            return EventResult.HANDLED;
        }
        if (editorPanel.handleNavigationKey(key)) {
            return EventResult.HANDLED;
        }
        if (projects.inputActive() && projectPanel.handleKey(key)) {
            return EventResult.HANDLED;
        }
        KeymapParts parts = overlays();
        Kind prompt = parts.stack().topPrompt().orElse(null);
        if (prompt == Kind.PROJECT_DELETE) {
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
        if (prompt == Kind.EDITOR_CLOSE) {
            answerEditorClose(key);
            return EventResult.HANDLED;
        }
        if (inputCaptured && key.isKey(KeyCode.ESCAPE)
                && (capturedWindow == null || !capturedWindow.shouldHandleKeyBeforeShell(key))) {
            releaseCapture();
            return EventResult.HANDLED;
        }
        if (prompt == Kind.VCS_PROMPT) {
            vcsActions.handleVcsPromptKey(promptKey(key));
            return EventResult.HANDLED;
        }
        if (vcsActions.writeInProgress()) {
            status = "Git operation in progress";
            return EventResult.HANDLED;
        }
        ToolWindow quitHolder = capturedWindow;
        boolean quitClaimed = inputCaptured && (quitHolder == null || quitHolder.claimsQuitChord());
        if (!quitClaimed && parts.keymap().matches(key, KeyActions.QUIT)) {
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
        Kind overlay = parts.stack().topOverlay().orElse(null);
        if (overlay != null) {
            handleOverlayKey(parts, overlay, key);
            return EventResult.HANDLED;
        }
        if (parts.keymap().matchesReserved(key, KeyActions.MENU)) {
            parts.stack().open(Kind.MENU, () -> openMenu(parts, 0));
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
            if (active != null && toolWindowHost.isFocused(active.id())
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
                int bottomIndex = toolWindowHost.activeIndex(ToolWindow.Anchor.BOTTOM);
                List<ToolWindow> bottomWindows = toolWindowHost.registered(ToolWindow.Anchor.BOTTOM);
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

    /**
     * Lets a background update wake the render loop, so queued work reaches the
     * screen without waiting for the idle tick or an input event.
     */
    public void bindRedraw(Runnable redraw) {
        uiWorkQueue.bindWake(redraw);
        ticker.bindRedraw(redraw);
    }

    /** One tick of the animations, as the shared tick runs it; a test drives it without waiting for the period. */
    void tickAnimations() {
        ticker.beat();
    }

    private void advanceAnimations() {
        projects.tickMarquee();
        mavenWindows.forEach(MavenToolWindow::tickMarquee);
    }

    private boolean animating() {
        return treeClipVisible() || mavenWindows.stream().anyMatch(this::mavenClipVisible);
    }

    private boolean treeClipVisible() {
        return shellLayout.isProjectVisible() && projects.marqueeMoving();
    }

    private boolean mavenClipVisible(MavenToolWindow window) {
        return toolWindowHost.activeWindow(window.anchor()) == window && window.marqueeMoving();
    }

    private String statusLine() {
        Optional<String> clipped = treeClipVisible()
                ? projects.selectedRelative().map(relative -> "Selected: " + relative)
                : mavenWindows.stream().filter(this::mavenClipVisible).findFirst()
                        .flatMap(MavenToolWindow::clippedText);
        if (clipped.isPresent()) {
            return clipped.get();
        }
        return runWindow != null && runWindow.debugging() ? "Debug | " + status : status;
    }

    private void dispatch(KeymapParts parts, String actionId) {
        dispatch(parts, actionId, null);
    }

    private void selectRunConfiguration(KeymapParts parts, String identifier) {
        if (runWindow != null && runWindow.selectConfiguration(identifier)) status = "Run configuration: " + identifier;
        parts.stack().close(Kind.RUN_CONFIGURATION);
    }

    private void dispatch(KeymapParts parts, String actionId, KeyEvent sourceKey) {
        if (isCommandAction(actionId) && !actionAllowed(actionSurface.get(), actionId)) {
            parts.stack().closeOverlays();
            status = "Action disabled for " + actionSurface.get().name().toLowerCase() + " surface: " + actionId;
            return;
        }
        for (ToolWindow window : toolWindows) {
            if (window.performAction(actionId, sourceKey)) {
                parts.stack().closeOverlays();
                return;
            }
        }
        if (projectPanel.performAction(actionId)) {
            parts.stack().closeOverlays();
            return;
        }
        Runnable closeOverlays = parts.stack()::closeOverlays;
        if (editorController.handle(actionId, closeOverlays)
                || vcsActions.handle(actionId, closeOverlays)
                || projectPanel.handle(actionId, closeOverlays)) {
            return;
        }
        switch (actionId) {
            case KeyActions.QUIT -> {
                parts.stack().closeOverlays();
                Runnable quit = quitAction;
                if (quit != null) {
                    quit.run();
                }
            }
            case KeyActions.SEARCH_EVERYWHERE -> {
                parts.stack().open(Kind.COMMAND_PALETTE, parts.commandPalette()::open);
                status = "Command palette: type to filter, Enter runs the action, Escape closes";
            }
            case KeyActions.KEYMAP_HELP -> openKeymapHelp(parts);
            case KeyActions.RUN_CHOOSE_CONFIGURATION -> {
                if (runWindow != null) {
                    parts.stack().open(Kind.RUN_CONFIGURATION,
                            () -> parts.runConfigurations().open(runWindow.selectedConfigurationLabel(), focusManager));
                    inputCaptured = false;
                }
            }
            case KeyActions.MENU -> {
                parts.stack().open(Kind.MENU, () -> openMenu(parts, 0));
                inputCaptured = false;
            }
            case KeyActions.IDEA_MCP_CONNECT -> {
                parts.stack().closeOverlays();
                ideaConnection.connect();
            }
            case KeyActions.FIND -> {
                parts.stack().closeOverlays();
                openEditorFind();
            }
            case KeyActions.FIND_IN_FILES -> {
                parts.stack().closeOverlays();
                openSearch(parts, ProjectSearchModel.Mode.TEXT);
            }
            case KeyActions.GO_TO_FILE -> {
                parts.stack().closeOverlays();
                openSearch(parts, ProjectSearchModel.Mode.FILES);
            }
            case KeyActions.GO_TO_LINE -> {
                parts.stack().closeOverlays();
                openGoToLine(parts);
            }
            case KeyActions.PROJECT_WIDER -> {
                parts.stack().closeOverlays();
                paneLayout.resizeProject(2);
            }
            case KeyActions.PROJECT_NARROWER -> {
                parts.stack().closeOverlays();
                paneLayout.resizeProject(-2);
            }
            case KeyActions.BOTTOM_TALLER -> {
                parts.stack().closeOverlays();
                paneLayout.resizeBottom(1);
            }
            case KeyActions.BOTTOM_SHORTER -> {
                parts.stack().closeOverlays();
                paneLayout.resizeBottom(-1);
            }
            case KeyActions.SWITCH_THEME -> {
                parts.stack().closeOverlays();
                status = "Theme: " + themes.switchToNextTheme();
            }
            case KeyActions.SHELL_SETTINGS -> {
                parts.stack().closeOverlays();
                parts.commandPalette().openWith("theme");
                status = "Settings: pick the shell theme (icons: " + fileIcons.mode().name() + ", set with vintelli.icons)";
            }
            default -> {
                if (actionId.startsWith("Toggle") && actionId.endsWith("ToolWindow")) {
                    for (ToolWindow tw : toolWindows) {
                        if (KeyActions.toggleToolWindow(tw.id()).equals(actionId)) {
                            toggleToolWindow(tw.id());
                            parts.stack().closeOverlays();
                            return;
                        }
                    }
                }
                OptionalInt toolWindowIndex = indexOfActivation(actionId);
                parts.stack().closeOverlays();
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

    private void openEditorFind() {
        if (editor == null || editor.activeDocument().isEmpty()) {
            status = "Open a file to find text in";
            return;
        }
        editor.openFind();
        toolWindowHost.clearFocus();
        inputCaptured = true;
        capturedWindow = editor;
        status = editor.status();
    }

    private void openGoToLine(KeymapParts parts) {
        if (editor == null || !editor.hasDocument()) {
            status = "Open a file to go to a line in";
            return;
        }
        inputCaptured = false;
        parts.stack().open(Kind.GO_TO_LINE, () -> openGoToLineInput(parts.goToLine()));
        status = parts.goToLine().status();
    }

    private void openGoToLineInput(GoToLineOverlay overlay) {
        overlay.open();
        if (editor != null) {
            editor.overlayInputActive(true);
        }
    }

    private void closeGoToLine(GoToLineOverlay overlay) {
        overlay.close();
        if (editor != null) {
            editor.overlayInputActive(false);
        }
    }

    private void handleGoToLineKey(KeymapParts parts, KeyEvent key) {
        GoToLineOverlay overlay = parts.goToLine();
        Optional<GoToLine> target = overlay.handleKey(key);
        if (overlay.isOpen()) {
            status = overlay.status();
            return;
        }
        target.ifPresent(position -> editor.goToLine(position.line(), position.column()));
        editor.overlayInputActive(false);
        editor.requestFocus();
        inputCaptured = false;
        status = target.isPresent() ? editor.status() : "Go to Line closed";
    }

    private void focusProjectPanel() {
        inputCaptured = false;
        capturedWindow = null;
        toolWindowHost.clearFocus();
        editorController.leaveNavigationBar();
        FocusManager manager = focusManager;
        if (manager != null) {
            manager.setFocus("project-panel");
        }
    }

    private void openSearch(KeymapParts parts, ProjectSearchModel.Mode mode) {
        if (parts.searchModel() == null) {
            status = "Project search unavailable";
            return;
        }
        inputCaptured = false;
        parts.stack().open(Kind.PROJECT_SEARCH, () -> parts.searchModel().open(mode));
        status = mode == ProjectSearchModel.Mode.FILES ? "Go to File: type to filter" : "Find in Files: type to search";
    }


    private void persistLayout() {
        paneLayout.persist();
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
        if (parts.stack().isOpen(Kind.COMMAND_PALETTE)) {
            parts.stack().close(Kind.COMMAND_PALETTE);
            status = "Command palette closed";
            return;
        }
        parts.stack().open(Kind.COMMAND_PALETTE, parts.commandPalette()::open);
        status = "Command palette: type to filter, Enter runs the action, Escape closes";
    }

    private void toggleKeymapHelp(KeymapParts parts) {
        inputCaptured = false;
        if (parts.stack().isOpen(Kind.KEYMAP_HELP)) {
            parts.stack().close(Kind.KEYMAP_HELP);
            status = "Keymap help closed";
            return;
        }
        openKeymapHelp(parts);
    }

    private void openKeymapHelp(KeymapParts parts) {
        parts.stack().open(Kind.KEYMAP_HELP, parts.keymapHelp()::open);
        status = "Keymap: " + parts.keymap().source();
    }

    private NavigationCapability availableNavigationProvider() {
        return navigationProviders.stream().filter(candidate -> candidate.health().isAvailable())
                .findFirst().orElse(null);
    }

    public void refreshVcs() {
        vcsActions.refresh();
    }

    private void refreshProblems() {
        toolWindows.stream().filter(ProblemsToolWindow.class::isInstance)
                .map(ProblemsToolWindow.class::cast)
                .forEach(ProblemsToolWindow::refreshInBackground);
    }

    private void restoreSessionThenRefreshProblems() {
        if (editor == null || toolWindows.stream().noneMatch(ProblemsToolWindow.class::isInstance)) {
            return;
        }
        editor.restoreSession();
        refreshProblems();
    }

    private void answerEditorClose(KeyEvent key) {
        int tabs = editor.tabs().size();
        editor.handleKey(key);
        status = editor.status();
        if (editor.tabs().size() < tabs) {
            refreshProblems();
        }
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

    private IdeaConnection.Host ideaHost() {
        return new IdeaConnection.Host() {
            @Override
            public void status(String message) {
                status = message;
            }

            @Override
            public void runOnUi(Runnable update) {
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
            }

            @Override
            public List<ToolWindow> toolWindows() {
                return toolWindows;
            }
        };
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

    private void switchToToolWindow(int globalIndex) {
        ToolWindow window = toolWindows.get(globalIndex);

        if (window.anchor() == ToolWindow.Anchor.BOTTOM) {
            toolIndex = globalIndex;
        }

        inputCaptured = window.capturesInput();
        capturedWindow = inputCaptured ? window : null;
        toolWindowHost.switchTo(window);
        state.selectToolWindow(window.id());
        status = "Tool window: " + window.title();
    }

    private boolean routeToToolWindow(KeyEvent key) {
        if (toolWindows.isEmpty()) {
            return false;
        }
        // Route to the active tool window of the bottom anchor (legacy behavior)
        int bottomIndex = toolWindowHost.activeIndex(ToolWindow.Anchor.BOTTOM);
        List<ToolWindow> bottomWindows = toolWindowHost.registered(ToolWindow.Anchor.BOTTOM);
        if (bottomWindows.isEmpty() || bottomIndex >= bottomWindows.size()) {
            return false;
        }
        ToolWindow active = bottomWindows.get(bottomIndex);
        if (active.capturesInput()) {
            return false;
        }
        if (active instanceof RunToolWindow && !toolWindowHost.isFocused(active.id())) {
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
        return toolWindowHost.activeWindow(anchor);
    }

    private void focusToolWindow(ToolWindow window) {
        toolWindowHost.focusWindow(window);
    }

    private static boolean isCommandAction(String actionId) {
        return KeyActions.RUN_DEBUG.equals(actionId) || KeyActions.RUN_EXECUTE.equals(actionId) || KeyActions.RUN_STOP.equals(actionId)
                || KeyActions.DEBUG_RESUME.equals(actionId) || KeyActions.DEBUG_PAUSE.equals(actionId)
                || KeyActions.DEBUG_STEP_OVER.equals(actionId) || KeyActions.DEBUG_STEP_INTO.equals(actionId)
                || KeyActions.DEBUG_STEP_OUT.equals(actionId)
                || KeyActions.MAVEN_ACTIVATE.equals(actionId) || KeyActions.TERMINAL_EXECUTE.equals(actionId)
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
        toolWindowHost.clearFocus();
        pendingProjectFocus = shellLayout.isProjectVisible();
        status = "Focus: shell navigation";
    }

    @Override
    public void highlight(ShellHighlightTarget target) {
        editorController.pushHighlight(target);
    }

    @Override
    public void show(ShellUnifiedDiff unifiedDiff) {
        editorController.pushDiff(unifiedDiff);
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
        toolWindowHost.reset();
        projects.resetSync();
        status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
        KeymapParts current = overlays;
        if (current != null) {
            current.stack().closeOverlays();
        }
    }

    public int toolIndex() {
        return toolWindowHost.activeIndex(ToolWindow.Anchor.BOTTOM);
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
        editorController.revealFile(file);
    }

    public void revealActiveFile() {
        editorController.revealActiveFile();
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
        public boolean isFocusable() {
            return RunConfigurationChooser.ID.equals(id);
        }

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
                if (projectOwner != null && projectOwner.paneLayout.dragging()) {
                    projectOwner.paneLayout.cancel();
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
