package dev.omatheusmesmo.vintelli.tui;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.PermissionChoice;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.PermissionOption;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.PermissionRequest;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.ToolCall;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.Update;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownView;
import dev.tamboui.style.Style;
import dev.tamboui.style.Overflow;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/** Persistent ACP chat sessions, streamed into the terminal shell. */
@Singleton
public final class AcpAgentsToolWindow implements ToolWindow {

    private static final String CHAT_GLYPH = "";
    private static final int MAX_BLOCKS = 250;
    private static final int MAX_DIFF_LINES = 80;
    private static final int MAX_PROMPT_CHARS = 32_000;
    private static final int TAB_ROW = 0;
    private static final int TRANSCRIPT_TOP = 1;
    private static final int CONTENT_INSET = 1;
    private static final Duration DEFAULT_PERMISSION_TIMEOUT = Duration.ofMinutes(10);
    private static final String ACP_NEW = KeyActions.ACP_CHAT_NEW;
    private static final String ACP_SEND = KeyActions.ACP_CHAT_SEND;
    private static final String ACP_CANCEL = KeyActions.ACP_CHAT_CANCEL;
    private static final String ACP_PERMISSION = KeyActions.ACP_CHAT_PERMISSION;
    private static final String ACP_CLOSE = KeyActions.ACP_CHAT_CLOSE;
    private static final String ACP_SELECT_TAB = KeyActions.ACP_CHAT_SELECT_TAB;

    private final Provider<ShellThemes> themes;
    private final AcpChatPort chat;
    private final UiWorkQueue uiWork;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final List<ChatSession> sessions = new ArrayList<>();
    private final List<TabHit> renderedTabs = new ArrayList<>();
    private final List<AgentHit> renderedAgents = new ArrayList<>();
    private List<AcpChatPort.Agent> agentChoices = List.of();
    private boolean agentPickerOpen;
    private int selectedAgent;
    private Rect lastArea = Rect.ZERO;
    private int activeIndex = -1;
    private int sessionSequence;
    private int scrollOffset;
    private int permissionChoiceIndex;
    private int actionIndex = -1;
    private int promptRow = -1;
    private int permissionStartRow = -1;
    private List<Integer> permissionChoiceByVisibleRow = List.of();
    private String input = "";
    private String notice = "";
    private volatile Consumer<Buffer> renderObserver;
    private volatile Duration permissionTimeout = DEFAULT_PERMISSION_TIMEOUT;

    /** Standalone construction for registry tests without an ACP service. */
    public AcpAgentsToolWindow(Provider<ShellThemes> themes) {
        this(themes, null, new UiWorkQueue());
    }

    @Inject
    public AcpAgentsToolWindow(Provider<ShellThemes> themes, AcpChatPort chat, UiWorkQueue uiWork) {
        this.themes = themes;
        this.chat = chat;
        this.uiWork = uiWork;
    }

    private ShellThemes activeThemes() {
        return themes.get();
    }

    void observeRenderedBuffer(Consumer<Buffer> observer) {
        renderObserver = observer;
    }

    void permissionTimeout(Duration timeout) {
        permissionTimeout = timeout;
    }

    @Override public String id() { return "acp.agents"; }
    @Override public String title() { return "ACP Agents"; }
    @Override public char shortcut() { return '8'; }
    @Override public int ordinal() { return 3; }
    @Override public String icon() { return CHAT_GLYPH; }
    @Override public Anchor anchor() { return Anchor.RIGHT; }
    @Override public boolean capturesInput() { return chat != null; }

    @Override
    public String actionForKey(KeyEvent event) {
        if (agentPickerOpen) {
            if (event.isKey(KeyCode.ENTER)) return ACP_NEW;
            if (event.isKey(KeyCode.ESCAPE)) return ACP_CLOSE;
        }
        if (event.hasCtrl() && event.isCharIgnoreCase('n')) return ACP_NEW;
        if (event.hasCtrl() && event.isCharIgnoreCase('w')) return ACP_CLOSE;
        ChatSession active = activeSession();
        if (active != null && active.permission != null) {
            if (event.isKey(KeyCode.ENTER)) {
                actionIndex = permissionChoiceIndex;
                return ACP_PERMISSION;
            }
            if (event.code() == KeyCode.CHAR && event.codePoint() >= '0' && event.codePoint() <= '9') {
                int options = active.permission.request.options().size();
                int choice = event.codePoint() == '0' ? options : event.codePoint() - '1';
                if (choice > options) return null;
                actionIndex = choice;
                return ACP_PERMISSION;
            }
            return null;
        }
        if (event.isKey(KeyCode.ENTER)) {
            if (event.hasShift() && active != null && !active.prompting) return null;
            return active == null ? ACP_NEW : active.prompting ? ACP_CANCEL : ACP_SEND;
        }
        if (event.hasCtrl() && event.isCharIgnoreCase('c') && active != null && active.prompting) return ACP_CANCEL;
        return null;
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        if (agentPickerOpen) {
            if (event.isKey(KeyCode.UP)) {
                selectedAgent = Math.max(0, selectedAgent - 1);
                notice = "";
                return true;
            }
            if (event.isKey(KeyCode.DOWN)) {
                selectedAgent = Math.min(agentChoices.size() - 1, selectedAgent + 1);
                notice = "";
                return true;
            }
            return false;
        }
        if (event.isKey(KeyCode.PAGE_UP)) {
            scrollOffset = Math.max(0, scrollOffset + 8);
            return true;
        }
        if (event.isKey(KeyCode.PAGE_DOWN)) {
            scrollOffset = Math.max(0, scrollOffset - 8);
            return true;
        }
        ChatSession active = activeSession();
        if (active != null && active.permission != null) {
            if (event.isKey(KeyCode.UP)) {
                permissionChoiceIndex = Math.max(0, permissionChoiceIndex - 1);
                return true;
            }
            if (event.isKey(KeyCode.DOWN)) {
                permissionChoiceIndex = Math.min(active.permission.request.options().size(), permissionChoiceIndex + 1);
                return true;
            }
            return false;
        }
        if (active != null && !active.prompting) {
            if (event.isKey(KeyCode.BACKSPACE)) {
                if (!input.isEmpty()) {
                    int end = input.offsetByCodePoints(input.length(), -1);
                    input = input.substring(0, end);
                }
                return true;
            }
            if (event.isKey(KeyCode.ENTER) && event.hasShift()) {
                input = appendPrompt(input, "\n");
                return true;
            }
            if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()
                    && !Character.isISOControl(event.codePoint())) {
                input = appendPrompt(input, new String(Character.toChars(event.codePoint())));
                return true;
            }
        }
        return false;
    }

    private static String appendPrompt(String current, String addition) {
        return current.length() + addition.length() > MAX_PROMPT_CHARS ? current : current + addition;
    }

    @Override
    public String actionForMouse(MouseEvent event) {
        if (!event.isClick() || !event.isLeftButton() || !inside(lastArea, event.x(), event.y())) return null;
        int y = event.y() - lastArea.y();
        int x = event.x() - lastArea.x();
        if (agentPickerOpen) {
            for (AgentHit hit : renderedAgents) {
                if (event.y() == hit.row()) {
                    selectedAgent = hit.index();
                    return ACP_NEW;
                }
            }
            return null;
        }
        if (y == TAB_ROW) {
            for (TabHit tab : renderedTabs) {
                if (event.x() >= tab.start() && event.x() < tab.end()) {
                    actionIndex = tab.index();
                    return ACP_SELECT_TAB;
                }
            }
            if (x >= Math.max(1, lastArea.width() - 10)) return ACP_CLOSE;
            if (x >= Math.max(1, lastArea.width() - 20)) return ACP_NEW;
        }
        if (y == promptRow && x >= Math.max(1, lastArea.width() - 12)) return ACP_SEND;
        int permissionRow = y - permissionStartRow;
        if (permissionStartRow >= 0 && permissionRow >= 0
                && permissionRow < permissionChoiceByVisibleRow.size()) {
            int choice = permissionChoiceByVisibleRow.get(permissionRow);
            if (choice >= 0) {
                actionIndex = choice;
                return ACP_PERMISSION;
            }
        }
        return null;
    }

    @Override
    public boolean performAction(String actionId, KeyEvent event) {
        if (ACP_NEW.equals(actionId)) {
            openSession();
            return true;
        }
        if (ACP_CLOSE.equals(actionId)) {
            if (agentPickerOpen) {
                agentPickerOpen = false;
                notice = "";
                return true;
            }
            closeActiveSession();
            return true;
        }
        if (ACP_SELECT_TAB.equals(actionId)) {
            if (actionIndex >= 0 && actionIndex < sessions.size()) {
                activeIndex = actionIndex;
                input = "";
                scrollOffset = 0;
                return true;
            }
            return false;
        }
        if (ACP_SEND.equals(actionId)) {
            if (activeSession() == null || activeSession().prompting) {
                cancelActiveTurn();
            } else sendPrompt();
            return true;
        }
        if (ACP_CANCEL.equals(actionId)) {
            cancelActiveTurn();
            return true;
        }
        if (ACP_PERMISSION.equals(actionId)) {
            choosePermission(actionIndex);
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldHandleKeyBeforeShell(KeyEvent event) {
        return actionForKey(event) != null;
    }

    @Override
    public void render(Frame frame, Rect area) {
        try {
            renderContent(frame, area);
        } finally {
            Consumer<Buffer> observer = renderObserver;
            if (observer != null) observer.accept(frame.buffer());
        }
    }

    private void renderContent(Frame frame, Rect area) {
        uiWork.drain();
        lastArea = area;
        renderedTabs.clear();
        renderedAgents.clear();
        permissionStartRow = -1;
        permissionChoiceByVisibleRow = List.of();
        promptRow = Math.max(TRANSCRIPT_TOP, area.height() - 1);
        ShellThemes active = activeThemes();
        Style bodyStyle = active.resolver("tool-window").toStyle();
        frame.renderWidget(Paragraph.builder().text(Text.from(" ")).style(bodyStyle).build(), area);
        if (area.width() < 8 || area.height() < 3) return;
        renderTabs(frame, area, active);
        if (agentPickerOpen) {
            renderAgentPicker(frame, area);
            return;
        }
        ChatSession session = activeSession();
        if (session == null) {
            renderEmpty(frame, area, active);
            return;
        }
        renderTranscript(frame, area, session, active);
        renderPermission(frame, area, session, active);
        renderPrompt(frame, area, session, active);
    }

    private void renderTabs(Frame frame, Rect area, ShellThemes active) {
        List<Span> spans = new ArrayList<>();
        int x = area.x() + 1;
        for (int index = 0; index < sessions.size(); index++) {
            String label = (index == activeIndex ? "[ " : "  ") + sessions.get(index).title
                    + (index == activeIndex ? " ] " : "   ");
            spans.add(Span.styled(label, index == activeIndex ? active.resolver("tool-window-title").toStyle()
                    : active.resolver("tool-window").toStyle()));
            int width = label.length();
            renderedTabs.add(new TabHit(index, x, x + width));
            x += width;
        }
        String actions = "+ New    x Close";
        int actionX = area.x() + Math.max(1, area.width() - actions.length() - 1);
        if (sessions.isEmpty()) actionX = area.x() + 1;
        if (actionX >= x) spans.add(Span.styled(actions, active.resolver("tool-window-title").toStyle()));
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(spans))).style(active.resolver("tool-window").toStyle()).build(),
                new Rect(area.x() + CONTENT_INSET, area.y() + TAB_ROW, area.width() - 2 * CONTENT_INSET, 1));
    }

    private void renderEmpty(Frame frame, Rect area, ShellThemes active) {
        List<String> lines = new ArrayList<>();
        if (notice.isBlank()) {
            if (chat == null || !chat.isConfigured()) {
                lines.add("No ACP agent is ready.");
                lines.add("Press Enter to choose an agent and see its status.");
            } else {
                lines.add("Start an ACP session with [Ctrl+N] or click New.");
                lines.add("Each session runs the configured stdio agent through the ACP SDK.");
            }
        } else {
            lines.add(notice);
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(String.join("\n", lines))).build(),
                new Rect(area.x() + CONTENT_INSET, area.y() + TRANSCRIPT_TOP + 1,
                        area.width() - 2 * CONTENT_INSET, Math.max(1, promptRow - TRANSCRIPT_TOP - 1)));
    }

    private void renderAgentPicker(Frame frame, Rect area) {
        List<String> lines = new ArrayList<>();
        lines.add("Choose an agent [Enter] [Esc]");
        int availableRows = Math.max(1, area.height() - 5);
        int first = Math.max(0, selectedAgent - availableRows + 1);
        for (int index = first; index < Math.min(agentChoices.size(), first + availableRows); index++) {
            AcpChatPort.Agent agent = agentChoices.get(index);
            lines.add((index == selectedAgent ? "> " : "  ") + plainText(agent.name())
                    + (agent.available() ? " (ready)" : " (unavailable)"));
            renderedAgents.add(new AgentHit(index, area.y() + TRANSCRIPT_TOP + lines.size() - 1));
        }
        AcpChatPort.Agent selected = agentChoices.get(selectedAgent);
        frame.renderWidget(Paragraph.builder().text(Text.from(String.join("\n", lines))).build(),
                new Rect(area.x() + CONTENT_INSET, area.y() + TRANSCRIPT_TOP,
                        area.width() - 2 * CONTENT_INSET, area.height() - TRANSCRIPT_TOP));
        int detailsY = area.y() + TRANSCRIPT_TOP + lines.size();
        frame.renderWidget(Paragraph.builder().text(Text.from(plainText(notice.isBlank() ? selected.reason() : notice)))
                        .overflow(Overflow.WRAP_WORD).build(),
                new Rect(area.x() + CONTENT_INSET, detailsY, area.width() - 2 * CONTENT_INSET,
                        Math.max(0, area.bottom() - detailsY)));
    }

    private void renderTranscript(Frame frame, Rect area, ChatSession session, ShellThemes active) {
        int innerWidth = area.width() - 2 * CONTENT_INSET;
        int top = area.y() + TRANSCRIPT_TOP;
        int bottom = area.y() + promptRow;
        if (session.permission != null) bottom = Math.min(bottom, permissionRow(session) - 1);
        int height = Math.max(0, bottom - top);
        int used = 0;
        int skipped = 0;
        for (ChatBlock item : session.blocks) {
            if (item instanceof MarkdownBlock markdown) {
                MarkdownView view = MarkdownView.builder().source("**" + markdown.role + "**\n" + markdown.text)
                        .styleResolver(active.resolver("tool-window")).build();
                int blockHeight = Math.max(1, view.computeHeight(innerWidth));
                if (skipped < scrollOffset) {
                    int skip = Math.min(blockHeight, scrollOffset - skipped);
                    skipped += skip;
                    if (skip == blockHeight) continue;
                    view = MarkdownView.builder().source("**" + markdown.role + "**\n" + markdown.text)
                            .styleResolver(active.resolver("tool-window")).scroll(skip).build();
                    blockHeight -= skip;
                }
                if (used >= height) break;
                int visibleHeight = Math.min(blockHeight, height - used);
                frame.renderWidget(view, new Rect(area.x() + CONTENT_INSET, top + used, innerWidth, visibleHeight));
                used += blockHeight;
            } else if (item instanceof ToolBlock tool) {
                List<Line> lines = toolLines(tool, active);
                int blockHeight = lines.size() + 1;
                if (skipped < scrollOffset) {
                    int skip = Math.min(blockHeight, scrollOffset - skipped);
                    skipped += skip;
                    if (skip == blockHeight) continue;
                    lines = lines.subList(skip, lines.size());
                    blockHeight -= skip;
                }
                if (used >= height) break;
                int visibleHeight = Math.min(lines.size(), height - used);
                if (visibleHeight > 0) {
                    frame.renderWidget(Paragraph.builder().text(Text.from(lines.subList(0, visibleHeight))).build(),
                            new Rect(area.x() + CONTENT_INSET, top + used, innerWidth, visibleHeight));
                }
                used += blockHeight;
            } else if (item instanceof NoticeBlock status) {
                if (skipped++ < scrollOffset) continue;
                if (used >= height) break;
                frame.renderWidget(Paragraph.builder().text(Text.from(status.text)).style(active.resolver("tool-window-title").toStyle()).build(),
                        new Rect(area.x() + CONTENT_INSET, top + used++, innerWidth, 1));
            }
        }
        if (session.prompting && session.permission == null && used < height) {
            frame.renderWidget(Paragraph.builder().text(Text.from("Agent is responding… [Enter] cancel"))
                    .style(active.resolver("tool-window-title").toStyle()).build(),
                    new Rect(area.x() + CONTENT_INSET, top + used, innerWidth, 1));
        }
    }

    private static List<Line> toolLines(ToolBlock tool, ShellThemes active) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.styled("◆ " + tool.title + " · " + tool.name + " · " + tool.status,
                active.resolver("tool-window-title").toStyle()));
        if (!tool.diff.isBlank()) {
            String[] diffLines = tool.diff.split("\\R", -1);
            int limit = Math.min(diffLines.length, MAX_DIFF_LINES);
            for (int index = 0; index < limit; index++) {
                String diffLine = diffLines[index];
                String role = diffLine.startsWith("+") ? "editor-diff-added"
                        : diffLine.startsWith("-") ? "editor-diff-removed" : "editor-diff-title";
                lines.add(Line.styled(diffLine, active.resolver(role).toStyle()));
            }
            if (diffLines.length > limit) {
                lines.add(Line.styled("… " + (diffLines.length - limit) + " more diff lines", active.resolver("editor-diff-marker").toStyle()));
            }
        }
        return lines;
    }

    private void renderPermission(Frame frame, Rect area, ChatSession session, ShellThemes active) {
        PendingPermission pending = session.permission;
        if (pending == null || promptRow <= TRANSCRIPT_TOP) return;
        int row = permissionRow(session);
        List<Line> lines = new ArrayList<>();
        List<Integer> choicesByLine = new ArrayList<>();
        lines.add(Line.styled("Permission required: " + pending.request.toolTitle(), active.resolver("tool-window-title").toStyle()));
        choicesByLine.add(-1);
        List<PermissionOption> options = pending.request.options();
        for (int index = 0; index < options.size(); index++) {
            String prefix = index == permissionChoiceIndex ? "› " : "  ";
            lines.add(Line.styled(prefix + "[" + (index + 1) + "] " + options.get(index).label(),
                    active.resolver(index == permissionChoiceIndex ? "tool-window-title" : "tool-window").toStyle()));
            choicesByLine.add(index);
        }
        String decline = permissionChoiceIndex == options.size() ? "› [0] Decline" : "  [0] Decline";
        lines.add(Line.styled(decline, active.resolver(permissionChoiceIndex == options.size() ? "editor-diff-removed" : "tool-window").toStyle()));
        choicesByLine.add(options.size());
        int maxRows = Math.max(1, Math.min(lines.size(), promptRow - TRANSCRIPT_TOP));
        if (lines.size() > maxRows) {
            int selectedLine = permissionChoiceIndex < options.size() ? permissionChoiceIndex + 1 : lines.size() - 1;
            if (maxRows == 1) {
                lines = List.of(lines.get(selectedLine));
                permissionChoiceByVisibleRow = List.of(choicesByLine.get(selectedLine));
            } else {
                int bodyRows = maxRows - 1;
                int firstBodyRow = Math.max(1, selectedLine - bodyRows / 2);
                firstBodyRow = Math.min(firstBodyRow, lines.size() - bodyRows);
                List<Line> visibleLines = new ArrayList<>(maxRows);
                List<Integer> visibleChoices = new ArrayList<>(maxRows);
                visibleLines.add(lines.getFirst());
                visibleChoices.add(-1);
                visibleLines.addAll(lines.subList(firstBodyRow, firstBodyRow + bodyRows));
                visibleChoices.addAll(choicesByLine.subList(firstBodyRow, firstBodyRow + bodyRows));
                lines = visibleLines;
                permissionChoiceByVisibleRow = List.copyOf(visibleChoices);
            }
        } else {
            permissionChoiceByVisibleRow = List.copyOf(choicesByLine);
        }
        permissionStartRow = row - area.y();
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(),
                new Rect(area.x() + CONTENT_INSET, row, area.width() - 2 * CONTENT_INSET, lines.size()));
    }

    private int permissionRow(ChatSession session) {
        int count = session.permission.request.options().size() + 2;
        return Math.max(lastArea.y() + TRANSCRIPT_TOP, lastArea.y() + promptRow - count - 1);
    }

    private void renderPrompt(Frame frame, Rect area, ChatSession session, ShellThemes active) {
        String action = session.prompting ? " [Enter] Cancel" : " [Enter] Send";
        int promptWidth = Math.max(1, area.width() - 16);
        String shown = input.length() <= promptWidth ? input : "…" + input.substring(input.length() - promptWidth + 1);
        String text = "> " + shown + action;
        frame.renderWidget(Paragraph.builder().text(Text.from(text)).style(active.resolver("tool-window").toStyle()).build(),
                new Rect(area.x() + CONTENT_INSET, area.y() + promptRow, area.width() - 2 * CONTENT_INSET, 1));
    }

    private static boolean inside(Rect area, int x, int y) {
        return x >= area.x() && x < area.x() + area.width() && y >= area.y() && y < area.y() + area.height();
    }

    private ChatSession activeSession() {
        return activeIndex < 0 || activeIndex >= sessions.size() ? null : sessions.get(activeIndex);
    }

    private void openSession() {
        if (chat == null) {
            notice = "No ACP agent service is available.";
            return;
        }
        if (!agentPickerOpen) {
            agentChoices = chat.agents();
            if (agentChoices.isEmpty()) {
                notice = "No ACP agent is configured. Enable vintelli.acp.enabled and configure an agent.";
                return;
            }
            selectedAgent = 0;
            if (agentChoices.size() > 1) {
                agentPickerOpen = true;
                notice = "";
                return;
            }
        }
        AcpChatPort.Agent agent = agentChoices.get(selectedAgent);
        if (!agent.available()) {
            notice = agent.reason();
            return;
        }
        agentPickerOpen = false;
        String title = agent.id().equals("configured") ? "Chat " + (++sessionSequence)
                : plainText(agent.name()) + " " + (++sessionSequence);
        ChatSession session = new ChatSession("opening-" + UUID.randomUUID(), title);
        sessions.add(session);
        activeIndex = sessions.size() - 1;
        input = "";
        notice = "";
        worker.submit(() -> {
            try {
                String id = chat.openSession(agent.id(), update -> uiWork.execute(() -> applyUpdate(session, update)),
                        request -> waitForPermission(session, request));
                boolean closeLateSession;
                synchronized (session) {
                    session.id = id;
                    session.opening = false;
                    closeLateSession = session.closed && !session.closeScheduled;
                    if (closeLateSession) session.closeScheduled = true;
                }
                if (closeLateSession) {
                    chat.close(id);
                } else {
                    uiWork.execute(() -> {
                        if (!session.closed) session.addNotice("ACP session ready");
                    });
                }
            } catch (RuntimeException failure) {
                String message = detail(failure);
                uiWork.execute(() -> {
                    session.opening = false;
                    session.addNotice("Could not start ACP session: " + message);
                });
            }
        });
    }

    private PermissionChoice waitForPermission(ChatSession session, PermissionRequest request) {
        CompletableFuture<PermissionChoice> answer = new CompletableFuture<>();
        PendingPermission pending = new PendingPermission(sanitized(request), answer);
        uiWork.execute(() -> {
            if (session.closed) {
                answer.complete(PermissionChoice.cancel());
            } else {
                session.offerPermission(pending);
                permissionChoiceIndex = 0;
            }
        });
        try {
            Duration timeout = permissionTimeout;
            return answer.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PermissionChoice.cancel();
        } catch (TimeoutException unanswered) {
            uiWork.execute(() -> {
                if (session.expirePermission(pending)) {
                    session.addNotice("Permission request timed out and was declined: " + pending.request.toolTitle());
                    permissionChoiceIndex = 0;
                }
            });
            return PermissionChoice.cancel();
        } catch (ExecutionException failed) {
            return PermissionChoice.cancel();
        }
    }

    private static PermissionRequest sanitized(PermissionRequest request) {
        List<PermissionOption> options = request.options().stream()
                .map(option -> new PermissionOption(option.id(), plainText(option.label()))).toList();
        return new PermissionRequest(request.id(), plainText(request.toolTitle()), options);
    }

    /**
     * Replaces every terminal control character of agent-provided text, so a
     * hostile agent cannot smuggle escape sequences through the cell buffer.
     * Line feeds are kept for multi-line Markdown and diffs.
     */
    static String plainText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder plain = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> plain.appendCodePoint(
                codePoint != '\n' && Character.isISOControl(codePoint) ? ' ' : codePoint));
        return plain.toString();
    }

    private void sendPrompt() {
        ChatSession session = activeSession();
        String prompt = input.strip();
        if (session == null || session.opening || session.prompting || prompt.isBlank()) return;
        input = "";
        session.addMarkdown("You", prompt);
        session.prompting = true;
        scrollOffset = 0;
        worker.submit(() -> {
            try {
                chat.prompt(session.id, prompt);
            } catch (RuntimeException failure) {
                String message = detail(failure);
                uiWork.execute(() -> session.addNotice("Prompt failed: " + message));
            } finally {
                uiWork.execute(() -> {
                    session.prompting = false;
                    session.cancelPermissions();
                });
            }
        });
    }

    private void cancelActiveTurn() {
        ChatSession session = activeSession();
        if (session == null || !session.prompting || session.opening) return;
        session.addNotice("Cancellation requested");
        if (session.cancelPermissions()) permissionChoiceIndex = 0;
        worker.submit(() -> {
            try {
                chat.cancel(session.id);
            } catch (RuntimeException failure) {
                String message = detail(failure);
                uiWork.execute(() -> session.addNotice("Could not cancel turn: " + message));
            }
        });
    }

    private void choosePermission(int index) {
        ChatSession session = activeSession();
        if (session == null || session.permission == null) return;
        PermissionRequest request = session.permission.request;
        if (index >= 0 && index < request.options().size()) {
            PermissionOption option = request.options().get(index);
            session.resolvePermission(PermissionChoice.select(option.id()));
            session.addNotice("Permission selected: " + option.label());
        } else if (index == request.options().size()) {
            session.resolvePermission(PermissionChoice.cancel());
            session.addNotice("Permission declined");
        } else {
            return;
        }
        permissionChoiceIndex = 0;
    }

    private void closeActiveSession() {
        ChatSession session = activeSession();
        if (session == null) return;
        session.closed = true;
        session.cancelPermissions();
        boolean promptInFlight = session.prompting;
        sessions.remove(activeIndex);
        activeIndex = sessions.isEmpty() ? -1 : Math.min(activeIndex, sessions.size() - 1);
        input = "";
        String id;
        synchronized (session) {
            if (session.opening || session.closeScheduled) return;
            session.closeScheduled = true;
            id = session.id;
        }
        worker.submit(() -> {
            try {
                if (promptInFlight) chat.cancel(id);
                chat.close(id);
            } catch (RuntimeException failure) {
                String message = detail(failure);
                uiWork.execute(() -> notice = "Could not close ACP session: " + message);
            }
        });
    }

    private void applyUpdate(ChatSession session, Update update) {
        if (session.closed) return;
        if (update instanceof AcpChatPort.Message message) {
            session.addMarkdown("Agent", message.text());
        } else if (update instanceof ToolCall toolCall) {
            session.updateTool(toolCall);
        } else if (update instanceof AcpChatPort.TurnStopped stopped) {
            session.addNotice("Turn stopped: " + stopped.reason());
        } else if (update instanceof AcpChatPort.Failure failure) {
            session.addNotice("Agent error: " + failure.message());
        }
    }

    private static String detail(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @PreDestroy
    void shutdown() {
        for (ChatSession session : List.copyOf(sessions)) {
            session.closed = true;
            session.cancelPermissions();
        }
        worker.shutdownNow();
    }

    private static final class ChatSession {
        private volatile String id;
        private final String title;
        private final List<ChatBlock> blocks = new ArrayList<>();
        private final Map<String, ToolBlock> tools = new HashMap<>();
        private volatile PendingPermission permission;
        private final Deque<PendingPermission> queuedPermissions = new ArrayDeque<>();
        private volatile boolean opening = true;
        private boolean closeScheduled;
        private boolean prompting;
        private volatile boolean closed;

        private ChatSession(String id, String title) {
            this.id = id;
            this.title = title;
        }

        private synchronized void offerPermission(PendingPermission pending) {
            if (permission == null) {
                permission = pending;
            } else {
                queuedPermissions.add(pending);
            }
        }

        private synchronized void resolvePermission(PermissionChoice choice) {
            PendingPermission resolved = permission;
            permission = queuedPermissions.poll();
            if (resolved != null) resolved.answer.complete(choice);
        }

        private synchronized boolean expirePermission(PendingPermission pending) {
            if (!pending.answer.complete(PermissionChoice.cancel())) return false;
            if (permission == pending) {
                permission = queuedPermissions.poll();
            } else {
                queuedPermissions.remove(pending);
            }
            return true;
        }

        private synchronized boolean cancelPermissions() {
            boolean hadPending = permission != null || !queuedPermissions.isEmpty();
            if (permission != null) permission.answer.complete(PermissionChoice.cancel());
            queuedPermissions.forEach(queued -> queued.answer.complete(PermissionChoice.cancel()));
            queuedPermissions.clear();
            permission = null;
            return hadPending;
        }

        private void addMarkdown(String role, String rawText) {
            String text = plainText(rawText);
            if (text.isEmpty()) return;
            if (role.equals("Agent") && !blocks.isEmpty() && blocks.getLast() instanceof MarkdownBlock last
                    && last.role.equals(role)) {
                last.text += text;
            } else {
                blocks.add(new MarkdownBlock(role, text));
            }
            trim();
        }

        private void addNotice(String text) {
            blocks.add(new NoticeBlock(plainText(text)));
            trim();
        }

        private void updateTool(ToolCall update) {
            ToolBlock tool = tools.get(update.id());
            if (tool == null) {
                tool = new ToolBlock(plainText(update.title()), plainText(update.name()), plainText(update.status()),
                        plainText(update.diff()));
                tools.put(update.id(), tool);
                blocks.add(tool);
            } else {
                tool.title = plainText(update.title());
                tool.name = plainText(update.name());
                tool.status = plainText(update.status());
                if (!update.diff().isBlank()) tool.diff = plainText(update.diff());
            }
            trim();
        }

        private void trim() {
            while (blocks.size() > MAX_BLOCKS) {
                ChatBlock removed = blocks.removeFirst();
                if (removed instanceof ToolBlock tool) tools.values().remove(tool);
            }
        }
    }

    private sealed interface ChatBlock permits MarkdownBlock, ToolBlock, NoticeBlock {
    }

    private static final class MarkdownBlock implements ChatBlock {
        private final String role;
        private String text;
        private MarkdownBlock(String role, String text) { this.role = role; this.text = text; }
    }

    private static final class ToolBlock implements ChatBlock {
        private String title;
        private String name;
        private String status;
        private String diff;
        private ToolBlock(String title, String name, String status, String diff) {
            this.title = title; this.name = name; this.status = status; this.diff = diff;
        }
    }

    private record NoticeBlock(String text) implements ChatBlock { }
    private record PendingPermission(PermissionRequest request, CompletableFuture<PermissionChoice> answer) { }
    private record TabHit(int index, int start, int end) { }
    private record AgentHit(int index, int row) { }
}
