package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.omatheusmesmo.vintelli.tui.StatusBarLayout;
import dev.tamboui.layout.Rect;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The quick fix and generate popups: a small panel beside the caret that lists the actions of the request, or
 * says why there are none. The panel draws the state of {@link QuickFixController} and takes its title and
 * wording from the {@link CodeActionPopup} it serves; it holds no state of its own.
 */
public final class QuickFixOverlay {

    private static final int MIN_WIDTH = 28;
    private static final int MAX_WIDTH = 72;
    private static final String HINT = "Up/Down select, Enter applies, Escape closes";
    private static final Style LABEL_STYLE = Style.create();
    private static final Style SELECTION_STYLE = Style.create().reversed().bold();
    private static final Style NOTICE_STYLE = Style.create().bold();
    private static final Style HINT_STYLE = Style.create().dim().italic();
    private static final Style NEEDS_FIELDS_STYLE = Style.create().dim();
    private static final String NEEDS_FIELDS = " (needs field selection)";

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
        Block block = Block.builder().title(controller.popup().title()).borders(Borders.ALL)
                .borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = block.inner(panel);
        controller.layout(OverlayInput.region(panel), OverlayInput.region(inner));
        QuickFixMenu.View view = controller.view();
        frame.renderWidget(Paragraph.builder().text(Text.from(lines(view, inner.width()))).build(), inner);
    }

    private List<Line> lines(QuickFixMenu.View view, int width) {
        CodeActionPopup popup = controller.popup();
        List<Line> lines = new ArrayList<>();
        switch (view.state()) {
            case LOADING -> lines.add(plain(popup.loadingText(), LABEL_STYLE, width));
            case EMPTY -> lines.add(plain(popup.emptyText(), LABEL_STYLE, width));
            case UNAVAILABLE -> lines.add(plain(popup.noun() + " unavailable: " + view.message(), NOTICE_STYLE, width));
            case FAILED -> lines.add(plain(popup.noun() + " failed: " + view.message(), NOTICE_STYLE, width));
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
        return Line.from(Span.styled(StatusBarLayout.truncate(text, width), style));
    }

    private Line actionLine(CodeAction action, boolean selected, int width) {
        String kind = action.kind().isEmpty() ? "" : "  " + action.kind();
        String row = StatusBarLayout.truncate(" " + title(action) + kind, width);
        Style style = selected ? SELECTION_STYLE : controller.needsFieldSelection(action) ? NEEDS_FIELDS_STYLE
                : LABEL_STYLE;
        return Line.from(Span.styled(row + " ".repeat(Math.max(0, width - CharWidth.of(row))), style));
    }

    private int contentWidth(QuickFixMenu.View view) {
        CodeActionPopup popup = controller.popup();
        int width = switch (view.state()) {
            case ACTIONS -> view.actions().stream().mapToInt(action -> CharWidth.of(title(action)) + 3
                    + CharWidth.of(action.kind())).max().orElse(0);
            case UNAVAILABLE -> CharWidth.of(popup.noun() + " unavailable: " + view.message());
            case FAILED -> CharWidth.of(popup.noun() + " failed: " + view.message());
            default -> 0;
        };
        return Math.max(width, Math.max(CharWidth.of(view.notice()), CharWidth.of(HINT)));
    }

    private String title(CodeAction action) {
        if (controller.needsFieldSelection(action)) {
            return action.title() + NEEDS_FIELDS;
        }
        return action.edit() != null || action.command() == null ? action.title() : action.title() + " (command)";
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
