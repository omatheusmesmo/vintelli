package dev.omatheusmesmo.vintelli.tui.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.omatheusmesmo.vintelli.tui.editor.QuickFixOverlay;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcon;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
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

/**
 * Draws {@link NavigationTargets}: a small panel beside the caret with one row per target, the file icon, the file
 * name, its directory, the position and the source line. It holds no state of its own.
 */
public final class NavigationTargetsOverlay {

    private static final int MIN_WIDTH = 40;
    private static final int MAX_WIDTH = 110;
    private static final String SEPARATOR = "  ";
    private static final String NO_MATCH = "No matching targets";
    private static final Style SELECTED = Style.create().reversed().bold();
    private static final Style DIM = Style.create().dim();
    private static final Style NAME = Style.create().bold();
    private static final Style FILTER = Style.create().bold();

    private final NavigationTargets targets;
    private final FileIcons icons;

    public NavigationTargetsOverlay(NavigationTargets targets, FileIcons icons) {
        this.targets = targets;
        this.icons = icons;
    }

    /**
     * Draws the popup over the frame, next to the caret cell when there is one and centred otherwise.
     *
     * @param caret the caret cell in screen coordinates, empty when the caret is scrolled out of view
     */
    public void render(Frame frame, Rect area, Optional<Rect> caret) {
        if (!targets.isOpen() || area.width() < 12 || area.height() < 4) {
            return;
        }
        NavigationTargets.View preview = targets.view();
        int height = Math.min(area.height(), preview.bodyRows() + preview.headerRows() + 2);
        int width = Math.min(Math.max(contentWidth(preview) + 4, MIN_WIDTH), Math.min(MAX_WIDTH, area.width()));
        Rect panel = QuickFixOverlay.place(area, width, height, caret);
        frame.renderWidget(Clear.INSTANCE, panel);
        Block block = Block.builder().title(preview.title() + " (" + preview.total() + ")").borders(Borders.ALL)
                .borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = block.inner(panel);
        targets.layout(OverlayInput.region(panel), OverlayInput.region(inner));
        NavigationTargets.View view = targets.view();
        frame.renderWidget(Paragraph.builder().text(Text.from(lines(view, inner.width()))).build(), inner);
    }

    private List<Line> lines(NavigationTargets.View view, int width) {
        List<Line> lines = new ArrayList<>();
        if (view.headerRows() > 0) {
            lines.add(Line.from(Span.styled(fit("> " + view.filter(), width), FILTER)));
        }
        if (view.rows().isEmpty()) {
            lines.add(Line.from(Span.styled(fit(NO_MATCH, width), DIM)));
            return lines;
        }
        List<NavigationTarget> visible = view.visible();
        for (int row = 0; row < visible.size(); row++) {
            lines.add(rowLine(visible.get(row), view.firstVisible() + row == view.selected(), width));
        }
        return lines;
    }

    private Line rowLine(NavigationTarget target, boolean chosen, int width) {
        FileIcon icon = icons.iconFor(target.file().toString());
        Span marker = chosen ? Span.styled(icon.symbol(icons.mode()), SELECTED) : icon.span(icons.mode());
        int remaining = Math.max(0, width - icon.symbolWidth(icons.mode()));
        List<Span> spans = new ArrayList<>();
        spans.add(marker);
        remaining = append(spans, " " + target.name(), chosen ? SELECTED : NAME, remaining);
        if (!target.directory().isEmpty()) {
            remaining = append(spans, SEPARATOR + target.directory(), chosen ? SELECTED : DIM, remaining);
        }
        remaining = append(spans, SEPARATOR + target.position(), chosen ? SELECTED : DIM, remaining);
        remaining = append(spans, SEPARATOR + target.preview(), chosen ? SELECTED : Style.EMPTY, remaining);
        if (chosen && remaining > 0) {
            spans.add(Span.styled(" ".repeat(remaining), SELECTED));
        }
        return Line.from(spans);
    }

    private static int append(List<Span> spans, String text, Style style, int remaining) {
        String fitted = fit(text, remaining);
        if (!fitted.isEmpty()) {
            spans.add(Span.styled(fitted, style));
        }
        return remaining - CharWidth.of(fitted);
    }

    private int contentWidth(NavigationTargets.View view) {
        FileIcon icon = icons.iconFor("File.java");
        int widest = CharWidth.of(NO_MATCH);
        for (NavigationTarget target : view.rows()) {
            int row = icon.symbolWidth(icons.mode()) + 1 + CharWidth.of(target.name())
                    + (target.directory().isEmpty() ? 0 : SEPARATOR.length() + CharWidth.of(target.directory()))
                    + SEPARATOR.length() + CharWidth.of(target.position())
                    + SEPARATOR.length() + CharWidth.of(target.preview());
            widest = Math.max(widest, row);
        }
        return widest;
    }

    private static String fit(String value, int width) {
        if (width <= 0) {
            return "";
        }
        return CharWidth.of(value) <= width ? value : CharWidth.substringByWidth(value, width);
    }
}
