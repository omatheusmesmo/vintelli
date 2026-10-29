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
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * IntelliJ-like terminal shell: menu bar, tool window tabs, project list and
 * status bar. Renders on every frame and answers keyboard input through
 * {@link EventHandler}.
 */
@Singleton
public final class VintelliShell implements EventHandler {

    private static final List<String> TOOL_WINDOW_TITLES = List.of("Run", "Terminal", "Problems", "ACP Agents");
    private static final List<String> MENUS = List.of("File", "Edit", "View", "Navigate", "Code", "Refactor", "Run", "Tools", "VCS", "Help");
    private static final List<String> PROJECT_FILES = List.of(
            "vintelli/pom.xml",
            "VintelliMain.java",
            "VintelliShell.java",
            "VintelliMcpTools.java",
            "VintelliShellTest.java"
    );

    private static final Color BACKGROUND = Color.hex("#2b2b2b");
    private static final Color PANEL = Color.hex("#3c3f41");
    private static final Color FOREGROUND = Color.hex("#a9b7c6");
    private static final Color SUBTLE = Color.hex("#808080");
    private static final Color ACCENT = Color.hex("#cc7832");
    private static final Color SELECTION = Color.hex("#365880");
    private static final Color BRIGHT = Color.hex("#ffffff");

    private static final List<Span> MENU_SPANS = menuSpans();
    private static final List<Line> TOOL_WINDOW_TAB_TITLES = IntStream.range(0, TOOL_WINDOW_TITLES.size())
            .mapToObj(index -> Line.from(Span.raw(" " + (index + 1) + ". " + TOOL_WINDOW_TITLES.get(index) + " ")))
            .toList();
    private static final Tabs TOOL_TABS = Tabs.builder()
            .titles(TOOL_WINDOW_TAB_TITLES)
            .style(Style.create().fg(SUBTLE))
            .highlightStyle(Style.create().fg(BRIGHT).bg(SELECTION).bold())
            .divider(" ")
            .build();
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

    private final TabsState tabsState = new TabsState(0);
    private final ListState listState = new ListState();

    private volatile String status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";

    public VintelliShell() {
        listState.select(0);
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
        if (event instanceof KeyEvent key) {
            if (key.isQuit()) {
                runner.quit();
                return true;
            }
            if (selectToolWindow(key)) {
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
        }
        return false;
    }

    private boolean selectToolWindow(KeyEvent key) {
        String text = key.string();
        if (text.length() != 1) {
            return false;
        }
        char digit = text.charAt(0);
        if (digit < '1' || digit > '9') {
            return false;
        }
        int toolWindow = digit - '1';
        if (toolWindow >= TOOL_WINDOW_TITLES.size()) {
            return false;
        }
        tabsState.select(toolWindow);
        status = "Tool window: " + TOOL_WINDOW_TITLES.get(toolWindow);
        return true;
    }

    private void moveSelection(int delta) {
        int selected = fileIndex();
        int target = Math.clamp(selected + delta, 0, PROJECT_FILES.size() - 1);
        if (target != selected) {
            listState.select(target);
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
        frame.renderStatefulWidget(TOOL_TABS, area, tabsState);
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

    private static Block darculaBlock(String title, BorderType borderType) {
        return Block.builder()
                .title(Title.from(Span.styled(title, Style.create().fg(BRIGHT).bg(PANEL))))
                .borders(Borders.ALL)
                .borderType(borderType)
                .borderColor(SUBTLE)
                .build();
    }

    private void renderToolWindow(Frame frame, Rect area) {
        String content = switch (toolIndex()) {
            case 0 -> "IDEA run configurations will stream here (MCP: execute_run_configuration)";
            case 1 -> "IDEA integrated terminal panel (MCP: execute_terminal_command)";
            case 2 -> "Problems view: offline inspections via idea.sh inspect + MCP analysis tools";
            default -> "ACP agent sessions: opencode acp / claude-code-acp / pi-acp";
        };
        Paragraph toolWindow = Paragraph.builder()
                .text(Text.from(content))
                .style(Style.create().fg(FOREGROUND))
                .block(darculaBlock(TOOL_WINDOW_TITLES.get(toolIndex()), BorderType.ROUNDED))
                .build();
        frame.renderWidget(toolWindow, area);
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
}
