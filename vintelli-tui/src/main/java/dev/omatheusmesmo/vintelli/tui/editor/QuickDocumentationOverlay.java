package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Face;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Piece;
import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Row;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
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

/**
 * The quick documentation popup: a panel beside the caret that shows the signature of the symbol first and its
 * documentation below, scrolled with the arrow and page keys. Code is drawn in a code colour, bold text in bold, and
 * the states say why there is no documentation. The panel draws the state of {@link QuickDocumentationController} and
 * holds no state of its own.
 */
public final class QuickDocumentationOverlay {

    private static final String TITLE = "Quick Documentation";
    private static final int MIN_WIDTH = 28;
    private static final int MAX_WIDTH = 80;
    private static final int MIN_HEIGHT = 4;
    private static final Style PLAIN_STYLE = Style.create();
    private static final Style BOLD_STYLE = Style.create().bold();
    private static final Style CODE_STYLE = Style.create().fg(Color.CYAN);

    private final QuickDocumentationController controller;

    public QuickDocumentationOverlay(QuickDocumentationController controller) {
        this.controller = controller;
    }

    /**
     * Draws the popup over the frame, next to the caret cell when there is one and centred otherwise. The panel is at
     * most half the height of the area.
     *
     * @param caret the caret cell in screen coordinates, empty when the caret is scrolled out of view
     */
    public void render(Frame frame, Rect area, Optional<Rect> caret) {
        if (!controller.isOpen() || area.width() < 12 || area.height() < MIN_HEIGHT) {
            return;
        }
        QuickDocumentationMenu.View preview = controller.view();
        int width = Math.min(Math.max(contentWidth(preview) + 2, MIN_WIDTH), Math.min(MAX_WIDTH, area.width()));
        int bodyRows = bodyOf(preview, width - 2).size();
        int maxHeight = Math.min(area.height(), Math.max(MIN_HEIGHT, area.height() / 2));
        int height = Math.min(bodyRows + 2, maxHeight);
        Rect panel = place(area, width, height, caret);
        frame.renderWidget(Clear.INSTANCE, panel);
        Block block = Block.builder().title(TITLE).borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = block.inner(panel);
        controller.layout(OverlayInput.region(inner));
        frame.renderWidget(Paragraph.builder().text(Text.from(lines(controller.view(), inner.width()))).build(), inner);
    }

    private static List<Line> lines(QuickDocumentationMenu.View view, int width) {
        if (view.state() != QuickDocumentationMenu.State.DOCUMENTED) {
            return bodyOf(view, width).stream().map(QuickDocumentationOverlay::lineOf).toList();
        }
        int last = Math.min(view.rows().size(), view.firstRow() + view.visibleRows());
        return view.rows().subList(view.firstRow(), last).stream().map(QuickDocumentationOverlay::lineOf).toList();
    }

    private static List<Row> bodyOf(QuickDocumentationMenu.View view, int width) {
        if (view.state() == QuickDocumentationMenu.State.DOCUMENTED) {
            return HoverText.wrap(view.documentation(), width);
        }
        Face face = view.state() == QuickDocumentationMenu.State.UNAVAILABLE
                || view.state() == QuickDocumentationMenu.State.FAILED ? Face.BOLD : Face.PLAIN;
        return HoverText.wrap(List.of(new Row(List.of(new Piece(messageOf(view), face)))), width);
    }

    private static String messageOf(QuickDocumentationMenu.View view) {
        return switch (view.state()) {
            case LOADING -> "Loading...";
            case EMPTY -> "No documentation found";
            case UNAVAILABLE -> "Quick documentation unavailable: " + view.message();
            case FAILED -> "Quick documentation failed: " + view.message();
            case DOCUMENTED -> "";
        };
    }

    private static int contentWidth(QuickDocumentationMenu.View view) {
        if (view.state() == QuickDocumentationMenu.State.DOCUMENTED) {
            return view.documentation().stream().mapToInt(Row::width).max().orElse(0);
        }
        return CharWidth.of(messageOf(view));
    }

    private static Line lineOf(Row row) {
        List<Span> spans = row.pieces().stream()
                .map(piece -> Span.styled(piece.text(), styleOf(piece.face())))
                .toList();
        return Line.from(spans);
    }

    private static Style styleOf(Face face) {
        return switch (face) {
            case PLAIN -> PLAIN_STYLE;
            case BOLD -> BOLD_STYLE;
            case CODE -> CODE_STYLE;
        };
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
