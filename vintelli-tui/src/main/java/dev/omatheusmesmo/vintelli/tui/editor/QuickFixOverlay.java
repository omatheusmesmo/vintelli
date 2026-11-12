package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The quick fix popup: a small panel beside the caret that lists the actions of the request, or says why
 * there are none. The panel draws the state of {@link QuickFixController}; it holds no state of its own.
 */
public final class QuickFixOverlay {

    private static final int MIN_WIDTH = 28;
    private static final int MAX_WIDTH = 72;
    private static final String HINT = "Up/Down select, Enter applies, Escape closes";
    private static final Style LABEL_STYLE = Style.create();
    private static final Style SELECTION_STYLE = Style.create().reversed().bold();
    private static final Style NOTICE_STYLE = Style.create().bold();
    private static final Style HINT_STYLE = Style.create().dim().italic();

    private final QuickFixController controller;

    public QuickFixOverlay(QuickFixController controller) {
        this.controller = controller;
    }

    /**
     * Draws the popup over the frame, next to the caret cell when there is one and centred otherwise.
     *
     * @param caret the caret cell in screen coordinates, empty when the caret is scrolled out of view
     */
    public void render(Frame frame, Rect area, Optional<Rect> caret) {
        if (!controller.isOpen() || area.width() < 12 || area.height() < 4) {
            return;
        }
        QuickFixMenu.View preview = controller.view();
        int height = Math.min(area.height(), preview.bodyRows() + QuickFixMenu.FOOTER_ROWS + 2);
        int width = Math.min(Math.max(contentWidth(preview) + 4, MIN_WIDTH), Math.min(MAX_WIDTH, area.width()));
        Rect panel = place(area, width, height, caret);
        frame.renderWidget(Clear.INSTANCE, panel);
        Block block = Block.builder().title("Quick Fix").borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = block.inner(panel);
        controller.layout(OverlayInput.region(panel), OverlayInput.region(inner));
        QuickFixMenu.View view = controller.view();
        frame.renderWidget(Paragraph.builder().text(Text.from(lines(view, inner.width()))).build(), inner);
    }

    private static List<Line> lines(QuickFixMenu.View view, int width) {
        List<Line> lines = new ArrayList<>();
        switch (view.state()) {
            case LOADING -> lines.add(plain("Looking for quick fixes...", LABEL_STYLE, width));
            case EMPTY -> lines.add(plain("No quick fixes available", LABEL_STYLE, width));
            case UNAVAILABLE -> lines.add(plain("Quick fixes unavailable: " + view.message(), NOTICE_STYLE, width));
            case FAILED -> lines.add(plain("Quick fixes failed: " + view.message(), NOTICE_STYLE, width));
            case ACTIONS -> {
                List<CodeAction> visible = view.visible();
                for (int row = 0; row < visible.size(); row++) {
                    boolean selected = view.firstVisible() + row == view.selected();
                    lines.add(actionLine(visible.get(row), selected, width));
                }
            }
        }
        for (int row = lines.size(); row < view.bodyRows(); row++) {
            lines.add(Line.from(Span.styled("", LABEL_STYLE)));
        }
        lines.add(plain(view.notice(), NOTICE_STYLE, width));
        lines.add(plain(HINT, HINT_STYLE, width));
        return lines;
    }

    private static Line plain(String text, Style style, int width) {
        return Line.from(Span.styled(truncate(text, width), style));
    }

    private static Line actionLine(CodeAction action, boolean selected, int width) {
        String kind = action.kind().isEmpty() ? "" : "  " + action.kind();
        String row = truncate(" " + title(action) + kind, width);
        return Line.from(Span.styled(row + " ".repeat(Math.max(0, width - row.length())),
                selected ? SELECTION_STYLE : LABEL_STYLE));
    }

    private static int contentWidth(QuickFixMenu.View view) {
        int width = switch (view.state()) {
            case ACTIONS -> view.actions().stream().mapToInt(action -> title(action).length() + 3
                    + action.kind().length()).max().orElse(0);
            case UNAVAILABLE -> ("Quick fixes unavailable: " + view.message()).length();
            case FAILED -> ("Quick fixes failed: " + view.message()).length();
            default -> 0;
        };
        return Math.max(width, Math.max(view.notice().length(), HINT.length()));
    }

    private static String title(CodeAction action) {
        return QuickFixMenu.appliesEdit(action) ? action.title() : action.title() + " (needs a command)";
    }

    private static String truncate(String value, int width) {
        if (width <= 0) {
            return "";
        }
        return value.length() <= width ? value : value.substring(0, width);
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
