package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Kind;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
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
 * The completion popup: a panel beside the caret that lists the suggestions matching the typed prefix, or says
 * why there are none. Each row carries a one character kind marker, the label, and the detail dimmed on the
 * right; every cut is measured in display cells. The panel draws the state of {@link CompletionController} and
 * holds no state of its own.
 */
public final class CompletionOverlay {

    private static final int MIN_WIDTH = 24;
    private static final int MAX_WIDTH = 72;
    private static final int DETAIL_GAP = 2;
    private static final int MIN_DETAIL_CELLS = 4;
    private static final Style LABEL_STYLE = Style.create();
    private static final Style SELECTION_STYLE = Style.create().reversed().bold();
    private static final Style NOTICE_STYLE = Style.create().bold();
    private static final Style DETAIL_STYLE = Style.create().dim();

    private final CompletionController controller;

    public CompletionOverlay(CompletionController controller) {
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
        CompletionMenu.View preview = controller.view();
        int height = Math.min(area.height(), preview.bodyRows() + 2);
        int width = Math.min(Math.max(contentWidth(preview) + 4, MIN_WIDTH), Math.min(MAX_WIDTH, area.width()));
        Rect panel = place(area, width, height, caret);
        frame.renderWidget(Clear.INSTANCE, panel);
        Block block = Block.builder().title("Completion").borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = block.inner(panel);
        controller.layout(OverlayInput.region(panel), OverlayInput.region(inner));
        CompletionMenu.View view = controller.view();
        frame.renderWidget(Paragraph.builder().text(Text.from(lines(view, inner.width()))).build(), inner);
    }

    private static List<Line> lines(CompletionMenu.View view, int width) {
        List<Line> lines = new ArrayList<>();
        switch (view.state()) {
            case LOADING -> lines.add(plain("Loading...", LABEL_STYLE, width));
            case EMPTY -> lines.add(plain("No suggestions", LABEL_STYLE, width));
            case UNAVAILABLE -> lines.add(plain("Completion unavailable: " + view.message(), NOTICE_STYLE, width));
            case FAILED -> lines.add(plain("Completion failed: " + view.message(), NOTICE_STYLE, width));
            case ITEMS -> {
                List<Suggestion> visible = view.visible();
                for (int row = 0; row < visible.size(); row++) {
                    boolean selected = view.firstVisible() + row == view.selected();
                    lines.add(suggestionLine(visible.get(row), selected, width));
                }
            }
        }
        return lines;
    }

    private static Line plain(String text, Style style, int width) {
        return Line.from(Span.styled(StatusBarLayout.truncate(text, width), style));
    }

    private static Line suggestionLine(Suggestion suggestion, boolean selected, int width) {
        Style style = selected ? SELECTION_STYLE : LABEL_STYLE;
        String left = StatusBarLayout.truncate(" " + marker(suggestion.kind()) + " " + suggestion.label(), width);
        int leftCells = CharWidth.of(left);
        int room = width - leftCells - DETAIL_GAP;
        if (suggestion.detail().isBlank() || room < MIN_DETAIL_CELLS) {
            return Line.from(Span.styled(left + " ".repeat(Math.max(0, width - leftCells)), style));
        }
        String detail = StatusBarLayout.truncate(suggestion.detail(), room);
        int padding = Math.max(0, width - leftCells - CharWidth.of(detail));
        return Line.from(Span.styled(left + " ".repeat(padding), style),
                Span.styled(detail, selected ? SELECTION_STYLE.patch(DETAIL_STYLE) : DETAIL_STYLE));
    }

    private static int contentWidth(CompletionMenu.View view) {
        return switch (view.state()) {
            case ITEMS -> view.rows().stream().mapToInt(suggestion -> CharWidth.of(" " + marker(suggestion.kind())
                    + " " + suggestion.label()) + DETAIL_GAP + CharWidth.of(suggestion.detail())).max().orElse(0);
            case UNAVAILABLE -> CharWidth.of("Completion unavailable: " + view.message());
            case FAILED -> CharWidth.of("Completion failed: " + view.message());
            case LOADING -> CharWidth.of("Loading...");
            case EMPTY -> CharWidth.of("No suggestions");
        };
    }

    /**
     * The one character marker of a kind: {@code m} method, {@code f} field, {@code C} class, {@code i}
     * interface, {@code e} enum, {@code v} variable, {@code c} constant, {@code k} keyword, {@code s} snippet,
     * {@code M} module or file, a blank for every other kind.
     */
    static String marker(Kind kind) {
        return switch (kind) {
            case METHOD, FUNCTION, CONSTRUCTOR -> "m";
            case FIELD, PROPERTY -> "f";
            case CLASS, STRUCT -> "C";
            case INTERFACE -> "i";
            case ENUM, ENUM_MEMBER -> "e";
            case VARIABLE, VALUE -> "v";
            case CONSTANT -> "c";
            case KEYWORD -> "k";
            case SNIPPET -> "s";
            case MODULE, FILE, FOLDER -> "M";
            default -> " ";
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
