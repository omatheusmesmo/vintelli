package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellDiffViewPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellStateView;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import dev.omatheusmesmo.vintelli.tui.keymap.CommandPalette;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapHelpOverlay;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapRegistry;
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
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.RichTextElement;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.StackElement;
import dev.tamboui.toolkit.elements.TabsElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.GlobalEventHandler;
import dev.tamboui.toolkit.event.KeyEventHandler;
import dev.tamboui.toolkit.event.MouseEventHandler;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.style.Style;
import dev.tamboui.widgets.common.ScrollBarPolicy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * IntelliJ-like terminal shell: menu bar, tool window tabs, project panel,
 * editor panel and status bar. Runs at the Toolkit level: the runner gets the
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
 * <p>The tab bar and the key routing are built from every {@link ToolWindow}
 * the {@link ToolWindowRegistry} exposes, so adding a tool window never
 * changes this class: the window declares itself, gets discovered as a CDI
 * bean and shows up in tab order with a {@code tool-<id>} element id. The
 * project list is the single source of truth for the file selection: arrows,
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
    static final int PROJECT_PANEL_WIDTH = 34;

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
    private record KeymapParts(Keymap keymap, CommandPalette commandPalette, KeymapHelpOverlay keymapHelp) {
    }

    private final ShellThemes themes;
    private final ShellState state;
    private final List<ToolWindow> toolWindows;
    private final ReentrantLock frameLock = new ReentrantLock();
    private final Supplier<Keymap> keymapSupplier;
    private final StackElement rootBase;
    private final Element lockedRoot;
    private final Element lockedProjectList;
    private final TabsElement toolTabs;
    private final ListElement<?> projectList;
    private volatile Runnable quitAction;
    private volatile KeymapParts overlays;

    private volatile ShellHighlightTarget highlight;
    private volatile ShellUnifiedDiff diff;
    private volatile String status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
    private volatile boolean inputCaptured;
    private volatile int toolIndex;
    private volatile int syncedFileIndex = -1;

    @Inject
    public VintelliShell(ToolWindowRegistry registry, ShellState state, KeymapRegistry keymapRegistry,
            Instance<ShellThemes> themes) {
        this(registry.toolWindows(), state, keymapRegistry::get, themes.get());
    }

    /**
     * Direct construction shell used by tests and the offscreen inspection
     * surface. The list must already be in deterministic tab order, so it
     * should come from a {@link ToolWindowRegistry}, and the default keymap
     * profile drives it.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes) {
        this(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes);
    }

    /**
     * Direct construction with one explicit keymap, for tests that assert a
     * named profile or a user keymap source.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Keymap keymap, ShellThemes themes) {
        this(toolWindows, state, () -> keymap, themes);
    }

    /**
     * Lazy constructor: the keymap is built only when the shell first handles
     * a key or renders, so a headless or MCP-only process never builds it.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
            ShellThemes themes) {
        this.themes = themes;
        this.state = state;
        this.toolWindows = List.copyOf(toolWindows);
        this.keymapSupplier = keymapSupplier;
        if (!toolWindows.isEmpty()) {
            state.selectToolWindow(toolWindows.get(0).id());
            inputCaptured = toolWindows.get(0).capturesInput();
        }
        state.selectFile(PROJECT_FILES.get(0));
        syncedFileIndex = 0;
        this.toolTabs = toolWindowTabs();
        this.projectList = projectPanel();
        this.lockedProjectList = locked(projectList);
        this.rootBase = buildRootContent();
        this.lockedRoot = locked(rootBase);
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
                    overlays = new KeymapParts(keymap,
                            new CommandPalette(keymap.actions(), keymap),
                            new KeymapHelpOverlay(keymap));
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
        return stack;
    }

    private Element shellLayout() {
        Column layout = Toolkit.column().id("shell");
        layout.add(menuBar());
        layout.add(toolTabs);
        layout.add(mainArea());
        layout.add(Toolkit.lazy(this::activeToolWindowElement));
        layout.add(Toolkit.lazy(this::statusBarEntry));
        return layout;
    }

    private Row menuBar() {
        Row bar = Toolkit.row().id("menu-bar");
        bar.length(1);
        for (int index = 0; index < MENUS.size(); index++) {
            boolean selected = index == 0;
            TextElement entry = Toolkit.text(selected ? " " + MENUS.get(index) + " " : MENUS.get(index));
            entry.addClass(selected ? "menu-entry-active" : "menu-entry");
            bar.add(entry);
        }
        return bar;
    }

    private TabsElement toolWindowTabs() {
        TabsElement tabs = Toolkit.tabs(tabTitles());
        tabs.id("tool-tabs");
        tabs.length(1);
        tabs.divider(" ");
        tabs.padding("", "");
        tabs.selected(0);
        return tabs;
    }

    private List<String> tabTitles() {
        return toolWindows.stream()
                .map(window -> " " + window.shortcut() + ". " + window.title() + " ")
                .toList();
    }

    private Row mainArea() {
        Row row = Toolkit.row().id("main-area");
        row.fill();
        row.add(lockedProjectList);
        row.add(editorPanel());
        return row;
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

    private ListElement<?> projectPanel() {
        ListElement<?> panel = Toolkit.list(PROJECT_FILES);
        panel.id("project-panel");
        panel.addClass("tool-panel");
        panel.title("Project");
        panel.focusable();
        panel.length(PROJECT_PANEL_WIDTH);
        panel.scrollbar(ScrollBarPolicy.AS_NEEDED);
        panel.selected(0);
        return panel;
    }

    private Panel editorPanel() {
        return Toolkit.panel(Toolkit.lazy(this::editorContent))
                .title("Editor")
                .rounded()
                .id("editor-panel")
                .addClass("tool-panel")
                .focusable()
                .fill();
    }

    private Element activeToolWindowElement() {
        if (toolWindows.isEmpty()) {
            TextElement emptyWindows = Toolkit.text(EMPTY_TOOL_WINDOWS_TEXT);
            emptyWindows.id("tool-windows-empty");
            emptyWindows.length(TOOL_WINDOW_HEIGHT);
            return emptyWindows;
        }
        return new ToolWindowElement(toolWindows.get(toolIndex));
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

    @Override
    public EventResult handle(Event event) {
        frameLock.lock();
        try {
            return accept(event);
        } finally {
            frameLock.unlock();
        }
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
        OptionalInt activation = activationByKey(parts, key);
        if (activation.isPresent()) {
            switchToToolWindow(activation.getAsInt());
            return EventResult.HANDLED;
        }
        if (inputCaptured) {
            if (!toolWindows.isEmpty()) {
                toolWindows.get(toolIndex).handleKey(key);
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
            case KeyActions.MOVE_UP -> {
                closeOverlays(parts);
                moveSelection(-1);
            }
            case KeyActions.MOVE_DOWN -> {
                closeOverlays(parts);
                moveSelection(1);
            }
            default -> {
                OptionalInt toolWindowIndex = indexOfActivation(actionId);
                closeOverlays(parts);
                if (toolWindowIndex.isEmpty()) {
                    status = "Unhandled action: " + actionId;
                } else {
                    switchToToolWindow(toolWindowIndex.getAsInt());
                }
            }
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

    /**
     * Closes the overlays; the keymap runs the closing at every non overlay
     * dispatch, so the shell returns to navigation with the next action.
     */
    private boolean closeOverlays(KeymapParts parts) {
        parts.commandPalette().close();
        parts.keymapHelp().close();
        return false;
    }

    private void switchToToolWindow(int index) {
        ToolWindow window = toolWindows.get(index);
        toolIndex = index;
        inputCaptured = window.capturesInput();
        toolTabs.selected(index);
        state.selectToolWindow(window.id());
        status = "Tool window: " + window.title();
    }

    private boolean routeToToolWindow(KeyEvent key) {
        if (toolWindows.isEmpty()) {
            return false;
        }
        ToolWindow active = toolWindows.get(toolIndex);
        if (active.capturesInput()) {
            return false;
        }
        return active.handleKey(key);
    }

    private void moveSelection(int delta) {
        int selected = projectList.selected();
        int target = Math.clamp(selected + delta, 0, PROJECT_FILES.size() - 1);
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
        int selected = projectList.selected();
        if (selected == syncedFileIndex) {
            return;
        }
        syncedFileIndex = selected;
        state.selectFile(PROJECT_FILES.get(selected));
        status = "Selected: " + PROJECT_FILES.get(selected);
    }

    private void releaseCapture() {
        inputCaptured = false;
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
        List<Line> lines = new ArrayList<>();
        ShellHighlightTarget pushed = highlight;
        ShellUnifiedDiff pushedDiff = diff;
        if (pushed != null) {
            lines.add(Line.styled("> highlight: " + pushed.file() + ":" + pushed.line(),
                    styledRole("editor-push-highlight")));
        }
        if (pushedDiff != null) {
            renderDiff(lines, pushedDiff);
        } else if (pushed == null) {
            lines.add(Line.from(EDITOR_TEXT));
        }
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
        state.selectFile(PROJECT_FILES.get(0));
        syncedFileIndex = 0;
        status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
        KeymapParts current = overlays;
        if (current != null) {
            current.commandPalette().close();
            current.keymapHelp().close();
        }
    }

    public int toolIndex() {
        return toolIndex;
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
