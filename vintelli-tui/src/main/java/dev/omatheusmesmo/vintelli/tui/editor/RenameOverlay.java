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
 * The one line input of Rename Symbol, drawn like the Go to Line panel. It opens prefilled with the identifier under
 * the caret, and the name the language server suggests replaces that text as long as the user has not typed. Enter
 * confirms a non-empty name and closes; Escape cancels. The shell reads {@link #status()} for the status line.
 */
public final class RenameOverlay {

    static final String HINT = "New name for the symbol; Enter renames, Escape cancels";
    static final String EMPTY = "Type a name; Enter renames, Escape cancels";
    private static final int MAX_WIDTH = 52;
    private static final int HEIGHT = 4;

    private boolean open;
    private boolean typed;
    private String query = "";
    private String message = HINT;

    public void open(String suggestion) {
        open = true;
        typed = false;
        query = suggestion;
        message = HINT;
    }

    /**
     * Replaces the text with the name the server suggests, unless the user has already typed.
     */
    public void suggest(String name) {
        if (open && !typed && !name.isEmpty()) {
            query = name;
        }
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

    /** The confirmed name, which closes the input; empty while the input stays open or was cancelled. */
    public Optional<String> handleKey(KeyEvent key) {
        if (!open) {
            return Optional.empty();
        }
        switch (OverlayInput.key(key)) {
            case ESCAPE -> open = false;
            case ENTER -> {
                String name = query.strip();
                if (name.isEmpty()) {
                    message = EMPTY;
                    return Optional.empty();
                }
                open = false;
                return Optional.of(name);
            }
            case BACKSPACE -> {
                typed = true;
                query = query.isEmpty() ? query : query.substring(0, query.offsetByCodePoints(query.length(), -1));
            }
            case TEXT -> {
                typed = true;
                query += Character.toString(key.codePoint());
            }
            default -> {
            }
        }
        return Optional.empty();
    }

    public void render(Frame frame, Rect area) {
        if (!open || area.width() < 12 || area.height() < HEIGHT) {
            return;
        }
        int width = Math.min(MAX_WIDTH, area.width() - 2);
        Rect panel = new Rect(area.x() + (area.width() - width) / 2, area.y() + (area.height() - HEIGHT) / 2,
                width, HEIGHT);
        frame.renderWidget(Clear.INSTANCE, panel);
        frame.renderWidget(Block.builder().title("Rename Symbol").borders(Borders.ALL)
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
