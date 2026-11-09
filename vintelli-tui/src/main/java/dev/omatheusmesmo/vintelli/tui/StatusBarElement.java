package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import dev.omatheusmesmo.vintelli.tui.StatusBarLayout.Line;
import dev.omatheusmesmo.vintelli.tui.StatusBarLayout.Placement;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * TamboUI element of the status bar. It draws the segments of
 * {@link StatusBarLayout} from the live {@link StatusBar}, answers clicks from
 * the cells it drew, and shows the provider popup above the row while it is open.
 * Clicks are answered from the last render, so a click agrees with the screen.
 */
final class StatusBarElement implements Element {

    static final String ID = "status-bar";
    private static final String POPUP_TITLE = "Providers";
    private static final int POPUP_PADDING = 2;

    private final StatusBar bar;
    private final IntConsumer breadcrumbClicked;
    private volatile Rect renderedArea = Rect.ZERO;
    private volatile List<Placement> placements = List.of();
    private volatile Rect popupArea = Rect.ZERO;
    private volatile boolean popupOpen;

    StatusBarElement(StatusBar bar, IntConsumer breadcrumbClicked) {
        this.bar = bar;
        this.breadcrumbClicked = breadcrumbClicked;
    }

    boolean popupOpen() {
        return popupOpen;
    }

    /**
     * Answers a click at a cell of the status row or of the open popup, and
     * returns true when the click belongs to the status bar. A click outside the
     * open popup closes it and still returns false, so the click reaches the rest
     * of the shell.
     */
    boolean click(int x, int y) {
        if (popupOpen) {
            if (contains(popupArea, x, y)) {
                return true;
            }
            popupOpen = false;
        }
        Rect area = renderedArea;
        if (area.isEmpty() || y != area.y() || !contains(area, x, y)) {
            return false;
        }
        int column = x - area.x();
        for (Placement placement : placements) {
            if (column < placement.x() || column >= placement.right()) {
                continue;
            }
            if (StatusBar.PROVIDERS_ID.equals(placement.id())) {
                popupOpen = !popupOpen;
            } else if (placement.id().startsWith(StatusBar.BREADCRUMB_PREFIX)) {
                breadcrumbClicked.accept(Integer.parseInt(placement.id().substring(StatusBar.BREADCRUMB_PREFIX.length())));
            }
            return true;
        }
        return true;
    }

    /** Closes the popup on Escape. Returns true when the key was taken. */
    boolean escape(KeyEvent key) {
        if (popupOpen && key.isKey(KeyCode.ESCAPE)) {
            popupOpen = false;
            return true;
        }
        return false;
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        Buffer buffer = frame.buffer();
        Style base = context.currentStyle();
        buffer.fill(area, new Cell(" ", base));
        Line line = StatusBarLayout.layout(bar.left(), bar.divider(), bar.right(), bar.gap(), area.width());
        renderedArea = area;
        placements = line.placements();
        StatusBar.Navigation navigation = bar.navigation();
        for (Placement placement : line.placements()) {
            if (placement.text().isEmpty()) {
                continue;
            }
            Style style = isSelected(placement.id(), navigation) ? base.reversed() : base;
            buffer.setString(area.x() + placement.x(), area.y(), placement.text(), style);
        }
        renderPopup(buffer, area, base);
    }

    @Override
    public Constraint constraint() {
        return Constraint.length(1);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth, 1);
    }

    @Override
    public String id() {
        return ID;
    }

    private void renderPopup(Buffer buffer, Rect area, Style base) {
        popupArea = Rect.ZERO;
        if (!popupOpen) {
            return;
        }
        List<String> rows = new ArrayList<>();
        rows.add(POPUP_TITLE);
        rows.addAll(bar.providerLines());
        int contentWidth = 0;
        for (String row : rows) {
            contentWidth = Math.max(contentWidth, CharWidth.of(row));
        }
        int width = Math.min(area.width(), contentWidth + POPUP_PADDING);
        int height = Math.min(rows.size(), area.y() - buffer.area().y());
        if (width <= 0 || height <= 0) {
            return;
        }
        Rect popup = new Rect(area.right() - width, area.y() - height, width, height);
        popupArea = popup;
        Style popupStyle = base.reversed();
        buffer.fill(popup, new Cell(" ", popupStyle));
        for (int row = 0; row < height; row++) {
            String text = CharWidth.substringByWidth(rows.get(row), width - 1);
            Style style = row == 0 ? popupStyle.bold() : popupStyle;
            buffer.setString(popup.x() + 1, popup.y() + row, text, style);
        }
    }

    private static boolean isSelected(String id, StatusBar.Navigation navigation) {
        return navigation.focused() && id.startsWith(StatusBar.BREADCRUMB_PREFIX)
                && id.equals(StatusBar.BREADCRUMB_PREFIX + navigation.selected());
    }

    private static boolean contains(Rect area, int x, int y) {
        return !area.isEmpty() && x >= area.x() && x < area.right() && y >= area.y() && y < area.bottom();
    }
}
