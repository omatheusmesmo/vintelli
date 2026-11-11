package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.List;

/**
 * The one-row find bar docked above the editor status line, and the match highlight painted over the
 * text. Typed keys edit the query, Enter and F3 move to the next match, Shift+Enter, Shift+F3 and Up to
 * the previous one. Escape is handled by the editor, which owns the focus.
 */
final class EditorFindRow extends StyledElement<EditorFindRow> {
    static final String ID = "editor-find";
    private static final String MATCH_ROLE = "editor-find-match";
    private static final String CURRENT_ROLE = "editor-find-current";
    private static final String COUNT_ROLE = "editor-find-count";

    private final EditorFind find;

    EditorFindRow(EditorFind find) {
        this.find = find;
        id(ID).length(1);
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        Line line = Line.from(Span.raw(" Find: " + find.query()), Span.styled(" ", Style.EMPTY.reversed()),
                Span.styled("  " + find.describe(), EditorElement.roleStyle(context, COUNT_ROLE)));
        frame.renderWidget(Paragraph.builder().text(Text.from(List.of(line))).build(), area);
    }

    @Override
    public Size preferredSize(int width, int height, RenderContext context) {
        return Size.of(width, 1);
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        if (event.isKey(KeyCode.F3)) {
            moveMatch(event.hasShift());
            return EventResult.HANDLED;
        }
        switch (OverlayInput.key(event)) {
            case ENTER -> moveMatch(event.hasShift());
            case DOWN -> find.next();
            case UP -> find.previous();
            case BACKSPACE -> find.backspace();
            case TEXT -> find.type(event.codePoint());
            default -> {
            }
        }
        return EventResult.HANDLED;
    }

    private void moveMatch(boolean backward) {
        if (backward) {
            find.previous();
        } else {
            find.next();
        }
    }

    /** Paints every visible match over the text; the current match gets its own role. */
    void paintMatches(Buffer buffer, Rect area, EditorDocument document, RenderContext context) {
        if (!find.isOpenOn(document) || area.isEmpty()) {
            return;
        }
        TextAreaState state = document.state();
        Style matchStyle = EditorElement.roleStyle(context, MATCH_ROLE);
        Style currentStyle = EditorElement.roleStyle(context, CURRENT_ROLE);
        List<EditorFind.Match> matches = find.matches();
        int currentIndex = find.currentIndex();
        for (int index = 0; index < matches.size(); index++) {
            EditorFind.Match found = matches.get(index);
            int viewRow = document.viewRow(found.row());
            int screenRow = viewRow - state.scrollRow();
            if (viewRow < 0 || screenRow < 0 || screenRow >= area.height()) {
                continue;
            }
            String line = state.getLine(viewRow);
            int from = Math.clamp(state.scrollCol(), 0, line.length());
            int start = CharWidth.of(line.substring(from, Math.clamp(found.column(), from, line.length())));
            int end = CharWidth.of(line.substring(from,
                    Math.clamp(found.column() + found.length(), from, line.length())));
            Style style = index == currentIndex ? currentStyle : matchStyle;
            int y = area.y() + screenRow;
            for (int x = start; x < end && x < area.width(); x++) {
                buffer.set(area.x() + x, y, buffer.get(area.x() + x, y).patchStyle(style));
            }
        }
    }
}
