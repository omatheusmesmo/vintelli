package dev.omatheusmesmo.vintelli.tui;

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
import java.util.stream.IntStream;

/**
 * IntelliJ-like terminal shell: menu bar, tool window tabs, project list and
 * status bar. The tab bar and the key routing are built from every
 * {@link ToolWindow} the {@link ToolWindowRegistry} exposes, so adding a tool
 * window never changes this class: the window declares itself, gets discovered
 * as a CDI bean and shows up in tab order. Renders on every frame and answers
 * keyboard input through {@link EventHandler}.
 */
@Singleton
public final class VintelliShell implements EventHandler {

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

    private final ShellState state;
    private final List<ToolWindow> toolWindows;
    private final Tabs toolTabs;
    private final TabsState tabsState = new TabsState(0);
    private final ListState listState = new ListState();

    private volatile String status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";
    private volatile boolean inputCaptured;

    @Inject
    public VintelliShell(ToolWindowRegistry registry, ShellState state) {
        this(registry.toolWindows(), state);
    }

    /**
     * Direct construction shell used by tests and the offscreen inspection
     * surface. The list must already be in deterministic tab order, so it
     * should come from a {@link ToolWindowRegistry}.
     */
    public VintelliShell(List<ToolWindow> toolWindows, ShellState state) {
        this.state = state;
        this.toolWindows = List.copyOf(toolWindows);
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
        if (!(event instanceof KeyEvent key)) {
            return false;
        }
        if (inputCaptured) {
            if (key.isKey(KeyCode.ESCAPE)) {
                inputCaptured = false;
                status = "Focus: shell navigation";
                return true;
            }
            if (!toolWindows.isEmpty()) {
                toolWindows.get(toolIndex()).handleKey(key);
            }
            return true;
        }
        if (key.isQuit()) {
            runner.quit();
            return true;
        }
        if (selectToolWindow(key)) {
            return true;
        }
        if (routeToToolWindow(key)) {
            return true;
        }
        if (key.isUp() || key.isChar('k')) {
            moveSelection(-1);
            return true;
        }
        if (key.isDown() || key.isChar('j')) {
            moveSelection(1);
            return true;
        }
        return false;
    }

    private boolean selectToolWindow(KeyEvent key) {
        ToolWindow match = null;
        for (ToolWindow toolWindow : toolWindows) {
            if (toolWindow.shortcut() == key.character()) {
                match = toolWindow;
                break;
            }
        }
        if (match == null) {
            return false;
        }
        switchToToolWindow(toolWindows.indexOf(match));
        return true;
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
        Rect area = frame.area();
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
                        .text(Text.from(EDITOR_TEXT))
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
     * Read model of this shell; see {@link ShellStateView} for the plain-type
     * contract other surfaces consume.
     */
    public ShellState state() {
        return state;
    }
}
