package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.List;
import java.util.Optional;

/**
 * The one line input of Go to Line, drawn as a small centred panel like the project search.
 * Typed text edits the query; Enter confirms a valid target and closes, invalid input keeps the
 * panel open with a message; Escape cancels. The shell reads {@link #status()} for the status line.
 */
public final class GoToLineOverlay {

    static final String HINT = "Line or line:column, such as 42 or 42:7";
    static final String INVALID = "Not a position: try a line such as 42 or 42:7";
    private static final int MAX_WIDTH = 52;
    private static final int HEIGHT = 4;

    private boolean open;
    private String query = "";
    private String message = HINT;

    public void open() {
        open = true;
        query = "";
        message = HINT;
    }

    public void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    public String status() {
        return message;
    }

    /** The confirmed target, which closes the input; empty while the input stays open or was cancelled. */
    public Optional<GoToLine> handleKey(KeyEvent key) {
        if (!open) {
            return Optional.empty();
        }
        switch (OverlayInput.key(key)) {
            case ESCAPE -> open = false;
            case ENTER -> {
                return confirm();
            }
            case BACKSPACE -> query = query.isEmpty() ? query : query.substring(0, query.length() - 1);
            case TEXT -> query += Character.toString(key.codePoint());
            default -> {
            }
        }
        return Optional.empty();
    }

    private Optional<GoToLine> confirm() {
        Optional<GoToLine> target = GoToLine.parse(query);
        if (target.isEmpty()) {
            message = INVALID;
            return Optional.empty();
        }
        open = false;
        return target;
    }

    public void render(Frame frame, Rect area) {
        if (!open || area.width() < 12 || area.height() < HEIGHT) {
            return;
        }
        int width = Math.min(MAX_WIDTH, area.width() - 2);
        Rect panel = new Rect(area.x() + (area.width() - width) / 2, area.y() + (area.height() - HEIGHT) / 2,
                width, HEIGHT);
        frame.renderWidget(Clear.INSTANCE, panel);
        frame.renderWidget(Block.builder().title("Go to Line").borders(Borders.ALL)
                .borderType(BorderType.ROUNDED).build(), panel);
        Rect inner = new Rect(panel.x() + 1, panel.y() + 1, panel.width() - 2, panel.height() - 2);
        Line prompt = Line.from(Span.styled("> " + query, Style.create().bold()));
        Line hint = Line.from(Span.styled(fit(message, inner.width()), Style.create().dim()));
        frame.renderWidget(Paragraph.builder().text(Text.from(List.of(prompt, hint))).build(), inner);
    }

    private static String fit(String value, int width) {
        if (width <= 0) {
            return "";
        }
        return value.length() <= width ? value : value.substring(0, width);
    }
}
