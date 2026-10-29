package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellDiffViewPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import dev.omatheusmesmo.vintelli.tui.keymap.CommandPalette;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapHelpOverlay;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapRegistry;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.common.ScrollBarPolicy;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.list.ScrollMode;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * IntelliJ-like terminal shell: menu bar, tool window tabs, project list and
 * status bar. The tab bar and the key routing are built from every
 * {@link ToolWindow} the {@link ToolWindowRegistry} exposes, so adding a tool
 * window never changes this class: the window declares itself, gets discovered
 * as a CDI bean and shows up in tab order. It also implements the write ports
 * of {@code vintelli-api}: external surfaces such as the MCP server push a
 * {@link ShellHighlightTarget} and a {@link ShellUnifiedDiff} and the editor
 * panel paints them on the next frame. Renders on every frame and answers
 * keyboard input through {@link EventHandler}.
 *
 * <p>Renders on every frame and answers keyboard input through
 * {@link EventHandler}. {@link #render(Frame)} and
 * {@link #handle(Event, TuiRunner)} share one lock: TamboUI widget and layout
 * state is mutated while a frame is drawn, so the TUI event loop and external
 * threads such as the inspect render endpoint must never draw or route keys
 * concurrently. The port writes stay lock-free volatile assignments.
 *
 * <p>Every key is routed through the active {@link Keymap}: the shell matches
 * named actions instead of reading concrete keys, and the overlays (command
 * palette, keymap help) capture the keyboard while open. The reserved keymap
 * chords (palette, help, tool window activations) keep working while a
 * capturing input has focus, so a terminal-like tool window never locks the
 * user out of the shell. The keymap is resolved lazily through the
 * {@link KeymapRegistry}, so a malformed user keymap file never aborts a
 * headless or MCP-only process: only the {@code --tui} session builds it, and
 * fails there with a one-line message.
 */
@Singleton
public final class VintelliShell implements EventHandler, ShellHighlightPort, ShellDiffViewPort {

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

    private static final Color BACKGROUND = Color.hex("#2b2b2b");
    private static final Color SELECTION = Color.hex("#365880");
    private static final Color ACCENT = Color.hex("#cc7832");
    static final Color PANEL = Color.hex("#3c3f41");
    static final Color FOREGROUND = Color.hex("#a9b7c6");
    static final Color SUBTLE = Color.hex("#808080");
    static final Color BRIGHT = Color.hex("#ffffff");

    private static final List<Span> MENU_SPANS = menuSpans();
    private static final ListWidget PROJECT_LIST = ListWidget.builder()
            .items(PROJECT_FILES.toArray(String[]::new))
            .style(Style.create().fg(FOREGROUND))
            .highlightStyle(Style.create().fg(BACKGROUND).bg(BRIGHT).bold())
            .highlightSymbol("> ")
            .scrollMode(ScrollMode.AUTO_SCROLL)
            .scrollBarPolicy(ScrollBarPolicy.AS_NEEDED)
            .scrollbarThumbStyle(Style.create().fg(SUBTLE))
            .scrollbarTrackStyle(Style.create().fg(PANEL))
            .block(darculaBlock("Project", BorderType.PLAIN))
            .build();
    private static final String EDITOR_TEXT = "editor tool window lands with issue #31; vintelli needs no external editor";

    /**
     * The keymap-driven parts of the shell, built together the first time the
     * TUI session actually uses them.
     */
    private record KeymapParts(Keymap keymap, CommandPalette commandPalette, KeymapHelpOverlay keymapHelp) {
    }

    private final ShellState state;
    private final List<ToolWindow> toolWindows;
    private final ReentrantLock frameLock = new ReentrantLock();
    private final Supplier<Keymap> keymapSupplier;
    private final Tabs toolTabs;
    private final TabsState tabsState = new TabsState(0);
    private final ListState listState = new ListState();
    private volatile KeymapParts overlays;

    private volatile ShellHighlightTarget highlight;
    private volatile ShellUnifiedDiff diff;
    private volatile String status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
    private volatile boolean inputCaptured;

    @Inject
    public VintelliShell(ToolWindowRegistry registry, ShellState state, KeymapRegistry keymapRegistry) {
        this(registry.toolWindows(), state, keymapRegistry::get);
    }

    /**
     * Direct construction shell used by tests and the offscreen inspection
     * surface. The list must already be in deterministic tab order, so it
     * should come from a {@link ToolWindowRegistry}.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state) {
        this(toolWindows, state, Keymap.ofProfile(KeymapProfile.DEFAULT, toolWindows));
    }

    /**
     * Full control constructor: the keymap is passed explicitly so tests can
     * exercise each profile, and the overlays and the dispatching of every
     * named action are wired from it.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Keymap keymap) {
        this(toolWindows, state, () -> keymap);
    }

    /**
     * Lazy constructor: the keymap is built only when the shell first handles
     * a key or renders, so a headless or MCP-only process never builds it.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier) {
        this.state = state;
        this.toolWindows = List.copyOf(toolWindows);
        this.keymapSupplier = keymapSupplier;
        this.toolTabs = Tabs.builder()
                .titles(toolWindowTabTitles())
                .style(Style.create().fg(SUBTLE))
                .highlightStyle(Style.create().fg(BRIGHT).bg(SELECTION).bold())
                .divider(" ")
                .build();
        listState.select(0);
        if (!toolWindows.isEmpty()) {
            state.selectToolWindow(toolWindows.get(0).id());
            inputCaptured = toolWindows.get(0).capturesInput();
        }
        state.selectFile(PROJECT_FILES.get(0));
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

    private List<Line> toolWindowTabTitles() {
        return IntStream.range(0, toolWindows.size())
                .mapToObj(index -> {
                    ToolWindow toolWindow = toolWindows.get(index);
                    return Line.from(Span.raw(" " + toolWindow.shortcut() + ". " + toolWindow.title() + " "));
                })
                .toList();
    }

    private static List<Span> menuSpans() {
        List<Span> spans = new ArrayList<>();
        for (int index = 0; index < MENUS.size(); index++) {
            if (index > 0) {
                spans.add(Span.raw("  "));
            }
            spans.add(index == 0
                    ? Span.styled(" " + MENUS.get(index) + " ", Style.create().fg(BACKGROUND).bg(ACCENT).bold())
                    : Span.styled(MENUS.get(index), Style.create().fg(FOREGROUND)));
        }
        return List.copyOf(spans);
    }

    @Override
    public boolean handle(Event event, TuiRunner runner) {
        frameLock.lock();
        try {
            return route(event, runner);
        } finally {
            frameLock.unlock();
        }
    }

    private boolean route(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return false;
        }
        if (inputCaptured && key.isKey(KeyCode.ESCAPE)) {
            inputCaptured = false;
            status = "Focus: shell navigation";
            return true;
        }
        KeymapParts parts = overlays();
        if (!inputCaptured && parts.keymap().matches(key, KeyActions.QUIT)) {
            runner.quit();
            return true;
        }
        if (parts.keymap().matchesReserved(key, KeyActions.SEARCH_EVERYWHERE)) {
            toggleCommandPalette(parts);
            return true;
        }
        if (parts.keymap().matchesReserved(key, KeyActions.KEYMAP_HELP)) {
            toggleKeymapHelp(parts);
            return true;
        }
        if (parts.commandPalette().isOpen()) {
            parts.commandPalette().handleKey(key).ifPresent(action -> dispatch(parts, action.id(), runner));
            return true;
        }
        if (parts.keymapHelp().isOpen()) {
            parts.keymapHelp().handleKey(key);
            return true;
        }
        OptionalInt activation = activationByKey(parts, key);
        if (activation.isPresent()) {
            switchToToolWindow(activation.getAsInt());
            return true;
        }
        if (inputCaptured) {
            if (!toolWindows.isEmpty()) {
                toolWindows.get(toolIndex()).handleKey(key);
            }
            return true;
        }
        if (routeToToolWindow(key)) {
            return true;
        }
        if (parts.keymap().matches(key, KeyActions.MOVE_UP)) {
            moveSelection(-1);
            return true;
        }
        if (parts.keymap().matches(key, KeyActions.MOVE_DOWN)) {
            moveSelection(1);
            return true;
        }
        return false;
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

    /**
     * Runs one named action of the catalog. Palette confirmations land here,
     * so every action stays reachable from the palette regardless of which
     * chord the active profile or user file binds to it.
     */
    private void dispatch(KeymapParts parts, String actionId, TuiRunner runner) {
        switch (actionId) {
            case KeyActions.QUIT -> runner.quit();
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

    private void closeOverlays(KeymapParts parts) {
        parts.commandPalette().close();
        parts.keymapHelp().close();
    }

    private void switchToToolWindow(int toolWindowIndex) {
        ToolWindow toolWindow = toolWindows.get(toolWindowIndex);
        tabsState.select(toolWindowIndex);
        state.selectToolWindow(toolWindow.id());
        inputCaptured = toolWindow.capturesInput();
        status = "Tool window: " + toolWindow.title();
    }

    private boolean routeToToolWindow(KeyEvent key) {
        if (toolWindows.isEmpty()) {
            return false;
        }
        ToolWindow active = toolWindows.get(toolIndex());
        if (active.capturesInput()) {
            return false;
        }
        return active.handleKey(key);
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

    private Text editorText() {
        ShellHighlightTarget pushedHighlight = highlight;
        ShellUnifiedDiff pushedDiff = diff;
        List<Line> lines = new ArrayList<>();
        if (pushedHighlight != null) {
            lines.add(Line.styled("> highlight: " + pushedHighlight.file() + ":" + pushedHighlight.line(),
                    Style.create().fg(ACCENT).bold()));
        }
        if (pushedDiff != null) {
            appendDiff(lines, pushedDiff);
        } else if (pushedHighlight == null) {
            lines.add(Line.styled(EDITOR_TEXT, Style.create().fg(FOREGROUND)));
        }
        return Text.from(lines);
    }

    private static void appendDiff(List<Line> lines, ShellUnifiedDiff pushedDiff) {
        lines.add(Line.styled("diff: " + pushedDiff.title(), Style.create().fg(BRIGHT).bold()));
        String[] split = pushedDiff.unifiedDiff().split("\n", -1);
        int end = split.length;
        if (end > 1 && split[end - 1].isEmpty()) {
            end--;
        }
        int shown = Math.min(end, MAX_EDITOR_DIFF_LINES);
        for (int index = 0; index < shown; index++) {
            lines.add(Line.styled(clip(split[index]), diffStyle(split[index])));
        }
        if (end > shown) {
            lines.add(Line.styled("... (" + (end - shown) + " more lines)", Style.create().fg(SUBTLE)));
        }
    }

    private static String clip(String diffLine) {
        if (diffLine.length() <= MAX_EDITOR_LINE_LENGTH) {
            return diffLine;
        }
        return diffLine.substring(0, MAX_EDITOR_LINE_LENGTH) + " ...";
    }

    private static Style diffStyle(String diffLine) {
        if (diffLine.startsWith("@@") || diffLine.startsWith("diff ")) {
            return Style.create().fg(ACCENT);
        }
        if (diffLine.startsWith("+")) {
            return Style.create().fg(BRIGHT).bold();
        }
        if (diffLine.startsWith("-")) {
            return Style.create().fg(SUBTLE);
        }
        return Style.create().fg(FOREGROUND);
    }

    private void moveSelection(int delta) {
        int selected = fileIndex();
        int target = Math.clamp(selected + delta, 0, PROJECT_FILES.size() - 1);
        if (target != selected) {
            listState.select(target);
            state.selectFile(PROJECT_FILES.get(target));
            status = "Selected: " + PROJECT_FILES.get(target);
        }
    }

    public void render(Frame frame) {
        frameLock.lock();
        try {
            Rect area = frame.area();
            KeymapParts parts = overlays();
            frame.renderWidget(paintBackground(), area);
            List<Rect> vertical = Layout.vertical()
                    .constraints(
                            Constraint.length(1),
                            Constraint.length(1),
                            Constraint.fill(),
                            Constraint.length(9),
                            Constraint.length(1))
                    .split(area);

            renderMenuBar(frame, vertical.get(0));
            renderToolWindowTabs(frame, vertical.get(1));
            renderMainArea(frame, vertical.get(2));
            renderToolWindow(frame, vertical.get(3));
            renderStatusBar(frame, vertical.get(4));

            if (parts.commandPalette().isOpen()) {
                parts.commandPalette().render(frame, area);
            } else if (parts.keymapHelp().isOpen()) {
                parts.keymapHelp().render(frame, area);
            }
        } finally {
            frameLock.unlock();
        }
    }

    private Block paintBackground() {
        return Block.builder()
                .borders(Borders.NONE)
                .background(BACKGROUND)
                .build();
    }

    private void renderMenuBar(Frame frame, Rect area) {
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(MENU_SPANS)))
                        .style(Style.create().bg(PANEL))
                        .build(),
                area);
    }

    private void renderToolWindowTabs(Frame frame, Rect area) {
        frame.renderStatefulWidget(toolTabs, area, tabsState);
    }

    private void renderMainArea(Frame frame, Rect area) {
        List<Rect> horizontal = Layout.horizontal()
                .constraints(Constraint.length(34), Constraint.fill())
                .split(area);

        frame.renderStatefulWidget(PROJECT_LIST, horizontal.get(0), listState);
        frame.renderWidget(
                Paragraph.builder()
                        .text(editorText())
                        .style(Style.create().fg(FOREGROUND))
                        .block(darculaBlock("Editor", BorderType.ROUNDED))
                        .build(),
                horizontal.get(1));
    }

    private void renderToolWindow(Frame frame, Rect area) {
        if (toolWindows.isEmpty()) {
            frame.renderWidget(emptyToolWindowsParagraph(), area);
            return;
        }
        toolWindows.get(toolIndex()).render(frame, area);
    }

    private static Paragraph emptyToolWindowsParagraph() {
        return Paragraph.builder()
                .text(Text.from(EMPTY_TOOL_WINDOWS_TEXT))
                .style(Style.create().fg(FOREGROUND))
                .build();
    }

    static Block darculaBlock(String title, BorderType borderType) {
        return Block.builder()
                .title(Title.from(Span.styled(title, Style.create().fg(BRIGHT).bg(PANEL))))
                .borders(Borders.ALL)
                .borderType(borderType)
                .borderColor(SUBTLE)
                .build();
    }

    private void renderStatusBar(Frame frame, Rect area) {
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(" " + status))
                        .style(Style.create().bg(PANEL).fg(BRIGHT))
                        .build(),
                area);
    }

    public int toolIndex() {
        return tabsState.selected() == null ? 0 : tabsState.selected();
    }

    public int fileIndex() {
        return listState.selected() == null ? 0 : listState.selected();
    }

    public String status() {
        return status;
    }

    /**
     * Whether the command palette overlay currently captures the keyboard;
     * tests use it as the visual-free proxy for the rendered overlay.
     */
    public boolean paletteOpen() {
        return overlays().commandPalette().isOpen();
    }

    /**
     * Whether the keymap help overlay is open; tests use it to assert the
     * {@code KeymapHelp} action without reading pixels off the rendered frame.
     */
    public boolean helpOverlayOpen() {
        return overlays().keymapHelp().isOpen();
    }

    /**
     * Read model of this shell; see {@link ShellStateView} for the plain-type
     * contract other surfaces consume.
     */
    public ShellState state() {
        return state;
    }
}
