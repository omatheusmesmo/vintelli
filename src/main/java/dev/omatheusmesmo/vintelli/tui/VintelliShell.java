package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
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
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.inject.Singleton;

import java.util.List;

@Singleton
public final class VintelliShell implements EventHandler {

    private static final List<String> TOOL_WINDOWS = List.of("Run", "Terminal", "Problems", "ACP Agents");
    private static final List<String> MENUS = List.of("File", "Edit", "View", "Navigate", "Code", "Refactor", "Run", "Tools", "VCS", "Help");
    private static final List<String> PROJECT_FILES = List.of(
            "vintelli/pom.xml",
            "VintelliMain.java",
            "VintelliShell.java",
            "VintelliMcpTools.java",
            "VintelliShellTest.java"
    );

    private int toolIndex;
    private int fileIndex;
    private String status = "Vintelli ready | IDEA MCP: disconnected | ACP: stopped";

    @Override
    public boolean handle(Event event, TuiRunner runner) {
        if (event instanceof KeyEvent key) {
            if (key.isQuit()) {
                runner.quit();
                return false;
            }
            if (key.isChar('1') || key.isChar('2') || key.isChar('3') || key.isChar('4')) {
                toolIndex = Integer.parseInt(key.string()) - 1;
                status = "Tool window: " + TOOL_WINDOWS.get(toolIndex);
                return true;
            }
            if (key.isUp() || key.isChar('k')) {
                if (fileIndex > 0) {
                    fileIndex--;
                    updateFileStatus();
                }
                return true;
            }
            if (key.isDown() || key.isChar('j')) {
                if (fileIndex < PROJECT_FILES.size() - 1) {
                    fileIndex++;
                    updateFileStatus();
                }
                return true;
            }
        }
        return false;
    }

    private void updateFileStatus() {
        status = "Selected: " + PROJECT_FILES.get(fileIndex);
    }

    public void render(Frame frame) {
        Rect area = frame.area();
        List<Rect> vertical = Layout.vertical()
                .constraints(
                        Constraint.length(1),
                        Constraint.fill(),
                        Constraint.length(9),
                        Constraint.length(1),
                        Constraint.length(1))
                .split(area);

        renderMenuBar(frame, vertical.get(0));
        renderMainArea(frame, vertical.get(1));
        renderToolWindow(frame, vertical.get(2));
        renderTabBar(frame, vertical.get(3));
        renderStatusBar(frame, vertical.get(4));
    }

    private void renderMenuBar(Frame frame, Rect area) {
        Style selected = Style.create().cyan().bold();
        Style normal = Style.create().gray();
        Line line = Line.from(
                Span.styled(" " + MENUS.get(0) + " ", selected),
                Span.raw("   "),
                Span.styled(String.join("   ", MENUS.subList(1, MENUS.size())), normal));
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), area);
    }

    private void renderMainArea(Frame frame, Rect area) {
        List<Rect> horizontal = Layout.horizontal()
                .constraints(Constraint.length(32), Constraint.fill())
                .split(area);

        List<Line> items = PROJECT_FILES.stream()
                .map(this::projectItem)
                .toList();
        Paragraph project = Paragraph.builder()
                .text(Text.from(items))
                .block(Block.builder()
                        .title("Project")
                        .borders(Borders.ALL)
                        .borderType(BorderType.PLAIN)
                        .build())
                .build();

        Paragraph editor = Paragraph.builder()
                .text(Text.from("nvim owns editing. open nvim in the tmux pane next to vintelli"))
                .block(Block.builder()
                        .title("Editor (nvim)")
                        .borders(Borders.ALL)
                        .borderType(BorderType.ROUNDED)
                        .build())
                .build();

        frame.renderWidget(project, horizontal.get(0));
        frame.renderWidget(editor, horizontal.get(1));
    }

    private Line projectItem(String item) {
        int index = PROJECT_FILES.indexOf(item);
        if (index == fileIndex) {
            return Line.from(Span.styled("> " + item, Style.create().cyan().bold()));
        }
        return Line.from(Span.styled("  " + item, Style.create()));
    }

    private void renderToolWindow(Frame frame, Rect area) {
        String content = switch (toolIndex) {
            case 0 -> "IDEA run configurations will stream here (MCP: execute_run_configuration)";
            case 1 -> "IDEA integrated terminal panel (MCP: execute_terminal_command)";
            case 2 -> "Problems view: offline inspections via idea.sh inspect + MCP analysis tools";
            default -> "ACP agent sessions: opencode acp / claude-code-acp / pi-acp";
        };
        Paragraph toolWindow = Paragraph.builder()
                .text(Text.from(content))
                .block(Block.builder()
                        .title(TOOL_WINDOWS.get(toolIndex))
                        .borders(Borders.ALL)
                        .borderType(BorderType.ROUNDED)
                        .build())
                .build();
        frame.renderWidget(toolWindow, area);
    }

    private void renderTabBar(Frame frame, Rect area) {
        Style selected = Style.create().cyan().bold();
        Style normal = Style.create().gray();
        Span[] spans = new Span[TOOL_WINDOWS.size()];
        for (int i = 0; i < TOOL_WINDOWS.size(); i++) {
            String label = " " + (i + 1) + ". " + TOOL_WINDOWS.get(i) + " ";
            spans[i] = i == toolIndex ? Span.styled(label, selected) : Span.styled(label, normal);
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(spans))).build(), area);
    }

    private void renderStatusBar(Frame frame, Rect area) {
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(" " + status))
                        .style(Style.create().cyan())
                        .build(),
                area);
    }

    public int toolIndex() {
        return toolIndex;
    }

    public int fileIndex() {
        return fileIndex;
    }

    public String status() {
        return status;
    }
}
