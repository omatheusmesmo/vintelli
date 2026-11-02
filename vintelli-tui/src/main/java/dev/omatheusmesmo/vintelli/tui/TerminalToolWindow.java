package dev.omatheusmesmo.vintelli.tui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalSession;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/** One persistent shell session rendered in the Terminal tool window. */
@Singleton
public final class TerminalToolWindow implements ToolWindow {

    private static final Logger LOG = Logger.getLogger(TerminalToolWindow.class);
    private static final int MAX_INPUT_LENGTH = 4_096;

    private final Provider<ShellThemes> themes;
    private final CapabilityRegistry capabilities;
    private final StringBuilder input = new StringBuilder();
    private final List<String> history = new ArrayList<>();
    private int historyIndex;
    private int cursor;
    private TerminalCapability terminal;
    private TerminalSession session;
    private String status = "Press Enter to start an interactive shell. It starts in the project root and can leave it.";
    private String confirmationLine;

    /** CDI construction with the selected terminal capability. */
    @Inject
    public TerminalToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities) {
        this.themes = themes;
        this.capabilities = capabilities;
    }

    /** Small construction surface for renderer tests that do not run commands. */
    public TerminalToolWindow(Provider<ShellThemes> themes) {
        this(themes, null);
    }

    @Override
    public String id() {
        return "terminal";
    }

    @Override
    public String title() {
        return "Terminal";
    }

    @Override
    public char shortcut() {
        return '2';
    }

    @Override
    public int ordinal() {
        return 1;
    }

    @Override
    public Anchor anchor() {
        return Anchor.BOTTOM;
    }

    @Override
    public boolean capturesInput() {
        return true;
    }

    @Override
    public String actionForKey(KeyEvent key) {
        if (key.isKey(KeyCode.ENTER) || confirmationLine != null && key.isCharIgnoreCase('y')) {
            return KeyActions.TERMINAL_EXECUTE;
        }
        return null;
    }

    @Override
    public boolean performAction(String actionId, KeyEvent key) {
        if (!KeyActions.TERMINAL_EXECUTE.equals(actionId)) {
            return false;
        }
        return handleKey(key == null ? KeyEvent.ofKey(KeyCode.ENTER) : key);
    }

    @Override
    public boolean handleKey(KeyEvent key) {
        if (session == null || !session.isAlive()) {
            if (key.isKey(KeyCode.ENTER)) {
                if (session != null) {
                    session.close();
                    session = null;
                }
                startSession();
            }
            return true;
        }
        if (confirmationLine != null) {
            handleConfirmation(key);
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            submitLine();
        } else if (key.isKey(KeyCode.BACKSPACE)) {
            if (cursor > 0) {
                input.deleteCharAt(--cursor);
            }
        } else if (key.isKey(KeyCode.DELETE)) {
            if (cursor < input.length()) {
                input.deleteCharAt(cursor);
            }
        } else if (key.isKey(KeyCode.LEFT)) {
            cursor = Math.max(0, cursor - 1);
        } else if (key.isKey(KeyCode.RIGHT)) {
            cursor = Math.min(input.length(), cursor + 1);
        } else if (key.isKey(KeyCode.HOME)) {
            cursor = 0;
        } else if (key.isKey(KeyCode.END)) {
            cursor = input.length();
        } else if (key.isKey(KeyCode.UP)) {
            recallHistory(-1);
        } else if (key.isKey(KeyCode.DOWN)) {
            recallHistory(1);
        } else if (key.hasCtrl() && key.isCharIgnoreCase('c')) {
            input.setLength(0);
            cursor = 0;
            status = "Interrupt sent";
            session.write(new byte[] {0x03});
        } else if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()
                && key.codePoint() >= 32 && input.length() < MAX_INPUT_LENGTH) {
            String value = new String(Character.toChars(key.codePoint()));
            input.insert(cursor, value);
            cursor += value.length();
        }
        return true;
    }

    @Override
    public void render(Frame frame, Rect area) {
        if (session != null && session.isAlive()) {
            session.resize(area.width(), Math.max(5, area.height() - 2));
        }
        List<String> rows = new ArrayList<>();
        if (session != null) {
            rows.addAll(session.output());
            if (!session.isAlive()) {
                rows.add("Shell exited. Press Enter to start a new session.");
            }
        }
        if (rows.isEmpty()) {
            rows.add("Shell has access to the host as your user. The initial directory is the project root; commands can leave it.");
        }
        if (status != null && !status.isBlank()) {
            rows.add(status);
        }
        String editing = input.toString();
        rows.add("vintelli> " + editing.substring(0, Math.min(cursor, editing.length()))
                + "▏" + editing.substring(Math.min(cursor, editing.length())));
        int maxRows = Math.max(1, area.height() - 2);
        List<String> visible = rows.size() <= maxRows ? rows : rows.subList(rows.size() - maxRows, rows.size());

        ShellThemes activeThemes = themes.get();
        Style contentStyle = activeThemes.resolver("tool-window").toStyle();
        Title blockTitle = Title.from(Span.styled("Terminal", activeThemes.resolver("tool-window-title").toStyle()));
        Block.Builder block = Block.builder()
                .title(blockTitle)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .style(contentStyle);
        activeThemes.resolver("tool-window").borderColor().ifPresent(block::borderColor);
        frame.renderWidget(Paragraph.builder()
                .text(Text.from(String.join("\n", visible)))
                .style(contentStyle)
                .block(block.build())
                .build(), area);
    }

    private void startSession() {
        if (capabilities == null) {
            status = "Terminal capability is unavailable in this renderer";
            return;
        }
        CapabilitySelection<TerminalCapability> selection = capabilities.terminal();
        if (selection instanceof CapabilitySelection.Unavailable<TerminalCapability> unavailable) {
            status = "Terminal capability is unavailable: " + unavailable.reason();
            return;
        }
        terminal = ((CapabilitySelection.Selected<TerminalCapability>) selection).provider();
        CapabilityResult<TerminalSession> result = terminal.startSession(
                new TerminalCapability.TerminalSessionRequest(Path.of("."), true));
        if (result instanceof CapabilityResult.Failed<TerminalSession> failed) {
            status = failed.failure().reason() + (failed.failure().detail().isEmpty()
                    ? "" : ": " + failed.failure().detail());
            return;
        }
        session = ((CapabilityResult.Ok<TerminalSession>) result).value();
        status = "Interactive shell started. Ctrl+C interrupts the current command; Escape returns to navigation.";
        input.setLength(0);
        cursor = 0;
        historyIndex = 0;
    }

    private void submitLine() {
        String command = input.toString();
        if (terminal != null && terminal.requiresSessionCommandConfirmation(command)) {
            confirmationLine = command;
            status = "This command needs confirmation. Type y and press Enter to run it, or n to cancel.";
            input.setLength(0);
            cursor = 0;
            return;
        }
        sendLine(command);
    }

    private void handleConfirmation(KeyEvent key) {
        if (key.isKey(KeyCode.ENTER)) {
            if ("y".equalsIgnoreCase(input.toString().strip())) {
                sendLine(confirmationLine);
            } else {
                status = "Command cancelled";
            }
            confirmationLine = null;
            input.setLength(0);
            cursor = 0;
        } else if (key.isKey(KeyCode.BACKSPACE)) {
            if (cursor > 0) {
                input.deleteCharAt(--cursor);
            }
        } else if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt() && input.length() < 8) {
            String value = new String(Character.toChars(key.codePoint()));
            input.insert(cursor, value);
            cursor += value.length();
        }
    }

    private void sendLine(String command) {
        if (!command.isBlank()) {
            history.add(command);
            historyIndex = history.size();
        }
        session.write((command + "\r").getBytes(StandardCharsets.UTF_8));
        input.setLength(0);
        cursor = 0;
        status = "";
    }

    private void recallHistory(int delta) {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.clamp(historyIndex + delta, 0, history.size());
        input.setLength(0);
        if (historyIndex < history.size()) {
            input.append(history.get(historyIndex));
        }
        cursor = input.length();
    }

    @PreDestroy
    public void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException failure) {
                LOG.debug("terminal shell did not close cleanly", failure);
            }
            session = null;
        }
    }
}
