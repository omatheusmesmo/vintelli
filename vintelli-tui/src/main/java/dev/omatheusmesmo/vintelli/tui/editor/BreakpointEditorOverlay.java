package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.tui.Keys;
import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The small breakpoint editor opened on the caret breakpoint: a Condition, a Log message and a Hit
 * count field plus a Suspend toggle, drawn like the Go to Line panel. Up, Down and Tab move between
 * the fields, typed text edits the focused one and Space flips Suspend; Enter saves and Escape
 * cancels. A breakpoint that does not suspend is a logpoint, so Suspend off needs a log message and a
 * log message needs Suspend off, because a debug adapter logs or suspends but never both. An invalid
 * entry keeps the panel open with the reason, readable through {@link #status()}.
 */
public final class BreakpointEditorOverlay {

    /**
     * What Enter confirmed. Empty text fields clear the matching setting.
     *
     * @param condition Java condition that must hold to stop, or empty
     * @param logMessage message printed on each hit, or empty
     * @param hitCondition hit count such as {@code 3} or {@code >= 3}, or empty
     * @param suspend whether the program stops at the breakpoint; false makes it a logpoint
     */
    public record Edit(String condition, String logMessage, String hitCondition, boolean suspend) {

        public Edit {
            condition = condition == null ? "" : condition.strip();
            logMessage = logMessage == null ? "" : logMessage.strip();
            hitCondition = hitCondition == null ? "" : hitCondition.strip();
        }

        /** Why the edit cannot be applied, or null when it can. */
        public String problem() {
            if (!suspend && logMessage.isEmpty()) {
                return NEEDS_LOG_MESSAGE;
            }
            if (suspend && !logMessage.isEmpty()) {
                return NEEDS_NO_SUSPEND;
            }
            try {
                new DebugCapability.SourceBreakpoint("breakpoint", 1, null, "", null, null, condition, logMessage,
                        hitCondition);
            } catch (IllegalArgumentException invalid) {
                return invalid.getMessage();
            }
            return null;
        }
    }

    static final String KEYS = "Up/Down move, Space toggles Suspend, Enter saves, Esc cancels";
    static final String NEEDS_LOG_MESSAGE = "A breakpoint that does not suspend needs a log message";
    static final String NEEDS_NO_SUSPEND = "A log message makes a logpoint: turn Suspend off or clear the message";
    private static final String[] LABELS = { "Condition", "Log message", "Hit count", "Suspend" };
    private static final String[] HINTS = {
            "Java condition that must hold to stop, such as count > 3",
            "Printed on each hit; {expr} is evaluated, such as count={count}",
            "3 stops on the 3rd hit; also >= 3, > 3, == 3 or % 2 (every 2nd hit)",
            "On stops the program; off makes a logpoint that prints without stopping" };
    private static final int CONDITION = 0;
    private static final int LOG_MESSAGE = 1;
    private static final int HIT_COUNT = 2;
    private static final int SUSPEND = 3;
    private static final int MAX_WIDTH = 72;
    private static final int HEIGHT = 8;

    private boolean open;
    private final String[] fields = { "", "", "" };
    private boolean suspend = true;
    private int focus;
    private String message = KEYS;

    public void open(DebugCapability.SourceBreakpoint current) {
        open = true;
        fields[CONDITION] = current.condition();
        fields[LOG_MESSAGE] = current.logMessage();
        fields[HIT_COUNT] = current.hitCondition();
        suspend = !current.isLogpoint();
        focus = CONDITION;
        message = KEYS;
    }

    public void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /** The reason the last Enter was refused, or the key legend while the panel is open and unrefused. */
    public String status() {
        return message;
    }

    /** The confirmed edit; empty while the panel stays open, was refused or was cancelled. */
    public Optional<Edit> handleKey(KeyEvent key) {
        if (!open) {
            return Optional.empty();
        }
        if (Keys.is(key, KeyCode.TAB)) {
            move(key.hasShift() ? -1 : 1);
            return Optional.empty();
        }
        switch (OverlayInput.key(key)) {
            case ESCAPE -> open = false;
            case UP -> move(-1);
            case DOWN -> move(1);
            case ENTER -> {
                return confirm();
            }
            case BACKSPACE -> deleteLast();
            case TEXT -> type(key.codePoint());
            default -> {
            }
        }
        return Optional.empty();
    }

    private void move(int step) {
        focus = Math.floorMod(focus + step, LABELS.length);
        message = KEYS;
    }

    private void deleteLast() {
        if (focus != SUSPEND && !fields[focus].isEmpty()) {
            String value = fields[focus];
            fields[focus] = value.substring(0, value.offsetByCodePoints(value.length(), -1));
            message = KEYS;
        }
    }

    private void type(int codePoint) {
        if (focus == SUSPEND) {
            if (codePoint == ' ') {
                suspend = !suspend;
            }
        } else {
            fields[focus] += Character.toString(codePoint);
        }
        message = KEYS;
    }

    private Optional<Edit> confirm() {
        Edit edit = new Edit(fields[CONDITION], fields[LOG_MESSAGE], fields[HIT_COUNT], suspend);
        String problem = edit.problem();
        if (problem != null) {
            message = problem;
            return Optional.empty();
        }
        open = false;
        return Optional.of(edit);
    }

    public void render(Frame frame, Rect area) {
        if (!open || area.width() < 24 || area.height() < HEIGHT) {
            return;
        }
        int width = Math.min(MAX_WIDTH, area.width() - 2);
        Rect panel = new Rect(area.x() + (area.width() - width) / 2, area.y() + (area.height() - HEIGHT) / 2,
                width, HEIGHT);
        frame.renderWidget(Clear.INSTANCE, panel);
        frame.renderWidget(Block.builder().title("Breakpoint").borders(Borders.ALL)
                .borderType(BorderType.ROUNDED).build(), panel);
        Rect inner = new Rect(panel.x() + 1, panel.y() + 1, panel.width() - 2, panel.height() - 2);
        List<Line> lines = new ArrayList<>();
        for (int index = 0; index < LABELS.length; index++) {
            lines.add(row(index, inner.width()));
        }
        lines.add(Line.from(Span.styled(fit(HINTS[focus], inner.width()), Style.create().dim())));
        lines.add(Line.from(Span.styled(fit(message, inner.width()), Style.create().dim())));
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
    }

    private Line row(int index, int width) {
        String value = index == SUSPEND ? (suspend ? "[x]" : "[ ]")
                : fields[index] + (index == focus ? "_" : "");
        String text = (index == focus ? "> " : "  ") + String.format("%-12s", LABELS[index] + ":") + value;
        Style style = index == focus ? Style.create().bold() : Style.EMPTY;
        return Line.from(Span.styled(fit(text, width), style));
    }

    private static String fit(String value, int width) {
        if (width <= 0) {
            return "";
        }
        return value.length() <= width ? value : value.substring(0, width);
    }
}
