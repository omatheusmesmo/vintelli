package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Face;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Piece;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Row;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.ColorConverter;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.syntax.SyntaxTheme;

/**
 * The quick definition popup: a panel below the caret line, above it when there is no room, that shows the source of
 * the declaration with the colours of the editor and a header with the file, the line and, with several targets,
 * the position among them. The code is coloured by the same {@link EditorSyntaxHighlighter} as the editor text. The
 * panel draws the state of {@link QuickDefinitionController} and holds no state of its own besides the highlighter
 * cache.
 */
public final class QuickDefinitionOverlay {

    private static final String TITLE = "Quick Definition";
    private static final int MIN_WIDTH = 36;
    private static final int MAX_WIDTH = 100;
    private static final int MIN_HEIGHT = 4;
    private static final int PEEK_ROWS = 15;
    private static final Style BOLD_STYLE = Style.create().bold();
    private static final Style PLAIN_STYLE = Style.create();
    private static final String PAGING_HINT = "  Alt+Left / Alt+Right";

    private final QuickDefinitionController controller;
    private final BuiltinEditor editor;
    private final EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();

    public QuickDefinitionOverlay(QuickDefinitionController controller, BuiltinEditor editor) {
        this.controller = controller;
        this.editor = editor;
    }

    /**
     * Draws the popup over the frame, below the caret cell when there is room and above it otherwise, centred when
     * the caret is scrolled out of view. The code takes about fifteen rows and scrolls beyond that.
     *
     * @param caret the caret cell in screen coordinates, empty when the caret is scrolled out of view
     */
    public void render(Frame frame, Rect area, Optional<Rect> caret) {
        if (!controller.isOpen() || area.width() < 12 || area.height() < MIN_HEIGHT) {
            return;
        }
        QuickDefinitionMenu.View preview = controller.view();
        int width = Math.min(Math.max(contentWidth(preview) + 2, MIN_WIDTH), Math.min(MAX_WIDTH, area.width()));
        int maxHeight = Math.min(area.height(), Math.max(MIN_HEIGHT, area.height() / 2));
        int height = Math.min(innerRows(preview, width - 2) + 2, maxHeight);
        Rect panel = place(area, width, height, caret);
        frame.renderWidget(Clear.INSTANCE, panel);
        Block block = Block.builder().title(TITLE).borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = block.inner(panel);
        controller.layout(OverlayInput.region(inner));
        QuickDefinitionMenu.View view = controller.view();
        if (view.state() == QuickDefinitionMenu.State.PEEKING) {
            renderPeek(frame, inner, view);
        } else {
            frame.renderWidget(Paragraph.builder().text(Text.from(messageLines(view, inner.width()))).build(), inner);
        }
    }

    private void renderPeek(Frame frame, Rect inner, QuickDefinitionMenu.View view) {
        QuickDefinitionMenu.Peek peek = view.current();
        Rect header = new Rect(inner.x(), inner.y(), inner.width(), 1);
        Rect body = new Rect(inner.x(), inner.y() + 1, inner.width(), Math.max(0, inner.height() - 1));
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(headerOf(view, peek))))
                .style(BOLD_STYLE).overflow(Overflow.CLIP).build(), header);
        if (body.isEmpty()) {
            return;
        }
        List<Line> lines = highlighted(peek);
        int last = Math.min(lines.size(), view.firstRow() + body.height());
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.subList(view.firstRow(), last)))
                .style(codeStyle()).overflow(Overflow.CLIP).build(), body);
    }

    private List<Line> highlighted(QuickDefinitionMenu.Peek peek) {
        highlighter.editorPalette(editor == null ? null : editor.palette());
        return highlighter.highlight(peek.code(), EditorElement.language(peek.file().toString()), codeStyle(),
                SyntaxTheme.DEFAULTS);
    }

    private Style codeStyle() {
        ShellEditorPalette palette = editor == null ? null : editor.palette();
        if (palette == null) {
            return PLAIN_STYLE;
        }
        Style style = PLAIN_STYLE;
        Color background = ColorConverter.INSTANCE.convert(palette.background()).orElse(null);
        Color text = ColorConverter.INSTANCE.convert(palette.text()).orElse(null);
        if (background != null) {
            style = style.bg(background);
        }
        return text == null ? style : style.fg(text);
    }

    private static String headerOf(QuickDefinitionMenu.View view, QuickDefinitionMenu.Peek peek) {
        if (view.peeks().size() < 2) {
            return peek.title();
        }
        return peek.title() + "  " + (view.index() + 1) + "/" + view.peeks().size() + PAGING_HINT;
    }

    private static int innerRows(QuickDefinitionMenu.View view, int width) {
        if (view.state() == QuickDefinitionMenu.State.PEEKING) {
            return 1 + Math.min(PEEK_ROWS, view.current().lines().size());
        }
        return messageRows(view, width).size();
    }

    private static int contentWidth(QuickDefinitionMenu.View view) {
        if (view.state() != QuickDefinitionMenu.State.PEEKING) {
            return CharWidth.of(messageOf(view));
        }
        QuickDefinitionMenu.Peek peek = view.current();
        int code = peek.lines().stream().mapToInt(CharWidth::of).max().orElse(0);
        return Math.max(code, CharWidth.of(headerOf(view, peek)));
    }

    private static List<Line> messageLines(QuickDefinitionMenu.View view, int width) {
        return messageRows(view, width).stream().map(QuickDefinitionOverlay::lineOf).toList();
    }

    private static List<Row> messageRows(QuickDefinitionMenu.View view, int width) {
        Face face = view.state() == QuickDefinitionMenu.State.FAILED ? Face.BOLD : Face.PLAIN;
        return HoverText.wrap(List.of(new Row(List.of(new Piece(messageOf(view), face)))), width);
    }

    private static String messageOf(QuickDefinitionMenu.View view) {
        return view.state() == QuickDefinitionMenu.State.LOADING ? "Loading..." : view.message();
    }

    private static Line lineOf(Row row) {
        return Line.from(row.pieces().stream()
                .map(piece -> Span.styled(piece.text(),
                        piece.face() == Face.BOLD ? BOLD_STYLE : PLAIN_STYLE))
                .toList());
    }

    private static Rect place(Rect area, int width, int height, Optional<Rect> caret) {
        if (caret.isEmpty()) {
            return new Rect(area.x() + (area.width() - width) / 2, area.y() + (area.height() - height) / 2,
                    width, height);
        }
        Rect cell = caret.get();
        int x = Math.clamp(cell.x(), area.x(), Math.max(area.x(), area.right() - width));
        int below = cell.y() + 1;
        int y = below + height <= area.bottom() ? below : Math.max(area.y(), cell.y() - height);
        return new Rect(x, y, width, height);
    }
}
